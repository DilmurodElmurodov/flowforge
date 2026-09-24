package com.flowforge.core.idempotency;

import com.flowforge.core.common.JsonUtils;
import com.flowforge.core.security.UserPrincipal;
import com.flowforge.core.tenant.TenantContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AOP implementation of the {@link Idempotent} contract.
 *
 * <pre>
 *   key = X-Idempotency-Key  (scoped: {tenant}:{key})
 *   1. cached response for key?           -> fingerprint match ? replay : 422
 *   2. SETNX idempotency:{key}:lock       -> held by someone else ? 409
 *   3. re-check cache (lost race)         -> replay
 *   4. proceed(), cache ResponseEntity, release lock
 * </pre>
 * Failures are never cached: a client may retry with the same key after a 5xx.
 * The aspect runs after method-security interceptors so unauthorised calls are never cached.
 */
@Aspect
@Component
@Order(1000)
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private static final Set<String> REPLAYED_HEADERS = Set.of(HttpHeaders.LOCATION, HttpHeaders.CONTENT_TYPE);
    private static final String REPLAY_HEADER = "X-Idempotent-Replay";

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;

    public IdempotencyAspect(IdempotencyStore store, IdempotencyProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        Optional<String> rawKey = currentRequest().map(r -> r.getHeader(properties.header()))
                .filter(v -> !v.isBlank()).map(String::trim);
        if (rawKey.isEmpty()) {
            if (idempotent.required()) {
                throw new IdempotencyExceptions.MissingKeyException(
                        "Header " + properties.header() + " is required for this operation");
            }
            return joinPoint.proceed();
        }
        String clientKey = rawKey.get();
        if (clientKey.length() > properties.maxKeyLength()) {
            throw new IdempotencyExceptions.MissingKeyException(
                    "Header " + properties.header() + " must not exceed " + properties.maxKeyLength() + " characters");
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String scopedKey = scope() + ":" + clientKey;
        String fingerprint = fingerprint(method, joinPoint.getArgs());
        Duration ttl = idempotent.ttl().isBlank() ? properties.responseTtl() : Duration.parse(idempotent.ttl());

        Optional<IdempotencyRecord> cached = store.find(scopedKey);
        if (cached.isPresent()) {
            return replay(cached.get(), fingerprint, clientKey, method);
        }

        String lockToken = UUID.randomUUID().toString();
        if (!store.tryLock(scopedKey, lockToken, properties.lockTtl())) {
            throw new IdempotencyExceptions.InProgressException(clientKey);
        }
        try {
            // Another request may have completed between our cache miss and the lock acquisition.
            cached = store.find(scopedKey);
            if (cached.isPresent()) {
                return replay(cached.get(), fingerprint, clientKey, method);
            }
            Object result = joinPoint.proceed();
            store.save(scopedKey, capture(result, fingerprint), ttl);
            return result;
        } finally {
            store.unlock(scopedKey, lockToken);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------

    private Object replay(IdempotencyRecord record, String fingerprint, String clientKey, Method method) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyExceptions.KeyReuseException(clientKey);
        }
        log.debug("Replaying cached response for idempotency key '{}'", clientKey);
        HttpHeaders headers = new HttpHeaders();
        record.headers().forEach(headers::add);
        headers.add(REPLAY_HEADER, "true");
        Object body = record.body() == null ? null : deserialiseBody(record, method);
        return ResponseEntity.status(record.status()).headers(headers).body(body);
    }

    private static IdempotencyRecord capture(Object result, String fingerprint) {
        int status = HttpStatus.OK.value();
        Object body = result;
        Map<String, String> headers = new LinkedHashMap<>();
        if (result instanceof ResponseEntity<?> entity) {
            status = entity.getStatusCode().value();
            body = entity.getBody();
            entity.getHeaders().forEach((name, values) -> {
                if (REPLAYED_HEADERS.contains(name) && !values.isEmpty()) {
                    headers.put(name, values.get(0));
                }
            });
        }
        return new IdempotencyRecord(fingerprint, status, headers,
                body == null ? null : JsonUtils.toJson(body),
                body == null ? null : body.getClass().getName(),
                Instant.now());
    }

    /** Deserialises the cached body into the controller's declared {@code ResponseEntity<T>} type when possible. */
    private static Object deserialiseBody(IdempotencyRecord record, Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType parameterized
                && ResponseEntity.class.equals(parameterized.getRawType())) {
            Type bodyType = parameterized.getActualTypeArguments()[0];
            if (!(bodyType instanceof WildcardType)) {
                return JsonUtils.fromJson(record.body(), bodyType);
            }
        }
        if (record.bodyType() != null) {
            try {
                return JsonUtils.fromJson(record.body(), Class.forName(record.bodyType()));
            } catch (ClassNotFoundException ignored) {
                // fall through to a generic tree
            }
        }
        return JsonUtils.readTree(record.body());
    }

    /** Keys are isolated per tenant (and per user when authenticated) so keys can never collide across tenants. */
    private static String scope() {
        String tenant = TenantContext.get().map(UUID::toString).orElse("public");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return tenant + ":" + principal.getId();
        }
        return tenant;
    }

    static String fingerprint(Method method, Object[] args) {
        List<Object> relevant = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof ServletRequest || arg instanceof ServletResponse) {
                continue;
            }
            relevant.add(arg);
        }
        String material = method.getDeclaringClass().getName() + "#" + method.getName() + ":" + JsonUtils.toJson(relevant);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Optional<HttpServletRequest> currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return Optional.of(servlet.getRequest());
        }
        return Optional.empty();
    }
}
