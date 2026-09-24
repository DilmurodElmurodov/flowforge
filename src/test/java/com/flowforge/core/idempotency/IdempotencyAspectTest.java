package com.flowforge.core.idempotency;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyAspectTest {

    private static final String HEADER = "X-Idempotency-Key";
    private static final String KEY = "order-42";
    private static final String SCOPED_KEY = "public:" + KEY;

    @Mock IdempotencyStore store;
    @Mock ProceedingJoinPoint joinPoint;
    @Mock MethodSignature signature;

    private IdempotencyAspect aspect;
    private MockHttpServletRequest request;

    /** Stand-in controller so the aspect can inspect a real generic return type. */
    static class SampleController {
        @Idempotent
        public ResponseEntity<Payload> create(Payload body) {
            return ResponseEntity.created(URI.create("/things/1")).body(body);
        }

        @Idempotent(required = false)
        public ResponseEntity<Payload> optional(Payload body) {
            return ResponseEntity.ok(body);
        }
    }

    record Payload(String name, int quantity) {
    }

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        aspect = new IdempotencyAspect(store, new IdempotencyProperties(HEADER, Duration.ofHours(1), Duration.ofSeconds(30), 128));
        request = new MockHttpServletRequest("POST", "/things");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(joinPoint.getSignature()).thenReturn(signature);
        Method method = SampleController.class.getMethod("create", Payload.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new Payload("widget", 2)});
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void rejectsRequestWithoutKeyWhenRequired() throws Throwable {
        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("create")))
                .isInstanceOf(IdempotencyExceptions.MissingKeyException.class);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void proceedsWithoutKeyWhenOptional() throws Throwable {
        Method method = SampleController.class.getMethod("optional", Payload.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn(ResponseEntity.ok(new Payload("a", 1)));

        Object result = aspect.around(joinPoint, annotation("optional"));

        assertThat(result).isInstanceOf(ResponseEntity.class);
        verify(store, never()).tryLock(anyString(), anyString(), any());
    }

    @Test
    void firstRequestAcquiresLockExecutesAndCachesResponse() throws Throwable {
        request.addHeader(HEADER, KEY);
        when(store.find(SCOPED_KEY)).thenReturn(Optional.empty());
        when(store.tryLock(eq(SCOPED_KEY), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);
        ResponseEntity<Payload> original = ResponseEntity.created(URI.create("/things/1")).body(new Payload("widget", 2));
        when(joinPoint.proceed()).thenReturn(original);

        Object result = aspect.around(joinPoint, annotation("create"));

        assertThat(result).isSameAs(original);
        ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(store).save(eq(SCOPED_KEY), record.capture(), eq(Duration.ofHours(1)));
        assertThat(record.getValue().status()).isEqualTo(201);
        assertThat(record.getValue().headers()).containsEntry("Location", "/things/1");
        assertThat(record.getValue().body()).contains("\"name\":\"widget\"");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(store).tryLock(eq(SCOPED_KEY), token.capture(), any());
        verify(store).unlock(SCOPED_KEY, token.getValue());
    }

    @Test
    void replaysCachedResponseWithoutExecutingBusinessLogic() throws Throwable {
        request.addHeader(HEADER, KEY);
        IdempotencyRecord cached = cachedRecord(fingerprintOfCurrentRequest());
        when(store.find(SCOPED_KEY)).thenReturn(Optional.of(cached));

        Object result = aspect.around(joinPoint, annotation("create"));

        verify(joinPoint, never()).proceed();
        verify(store, never()).tryLock(anyString(), anyString(), any());
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(response.getHeaders().getFirst("Location")).isEqualTo("/things/1");
        assertThat(response.getBody()).isEqualTo(new Payload("widget", 2));
    }

    @Test
    void rejectsKeyReuseWithDifferentPayload() throws Throwable {
        request.addHeader(HEADER, KEY);
        when(store.find(SCOPED_KEY)).thenReturn(Optional.of(cachedRecord("different-fingerprint")));

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("create")))
                .isInstanceOf(IdempotencyExceptions.KeyReuseException.class);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void concurrentRequestWithSameKeyIsRejectedWhileLockIsHeld() throws Throwable {
        request.addHeader(HEADER, KEY);
        when(store.find(SCOPED_KEY)).thenReturn(Optional.empty());
        when(store.tryLock(eq(SCOPED_KEY), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("create")))
                .isInstanceOf(IdempotencyExceptions.InProgressException.class);
        verify(joinPoint, never()).proceed();
        verify(store, never()).unlock(anyString(), anyString());
    }

    @Test
    void lostRaceAfterLockAcquisitionReplaysTheWinnersResponse() throws Throwable {
        request.addHeader(HEADER, KEY);
        IdempotencyRecord winner = cachedRecord(fingerprintOfCurrentRequest());
        when(store.find(SCOPED_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(store.tryLock(eq(SCOPED_KEY), anyString(), any())).thenReturn(true);

        Object result = aspect.around(joinPoint, annotation("create"));

        verify(joinPoint, never()).proceed();
        assertThat(((ResponseEntity<?>) result).getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        verify(store).unlock(eq(SCOPED_KEY), anyString());
    }

    @Test
    void failuresAreNotCachedAndLockIsReleased() throws Throwable {
        request.addHeader(HEADER, KEY);
        when(store.find(SCOPED_KEY)).thenReturn(Optional.empty());
        when(store.tryLock(eq(SCOPED_KEY), anyString(), any())).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("create"))).isInstanceOf(IllegalStateException.class);

        verify(store, never()).save(anyString(), any(), any());
        verify(store).unlock(eq(SCOPED_KEY), anyString());
    }

    @Test
    void rejectsOverlongKeys() {
        request.addHeader(HEADER, "k".repeat(129));
        assertThatThrownBy(() -> aspect.around(joinPoint, annotation("create")))
                .isInstanceOf(IdempotencyExceptions.MissingKeyException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static Idempotent annotation(String method) throws NoSuchMethodException {
        return SampleController.class.getMethod(method, Payload.class).getAnnotation(Idempotent.class);
    }

    private String fingerprintOfCurrentRequest() throws NoSuchMethodException {
        return IdempotencyAspect.fingerprint(SampleController.class.getMethod("create", Payload.class), joinPoint.getArgs());
    }

    private static IdempotencyRecord cachedRecord(String fingerprint) {
        return new IdempotencyRecord(fingerprint, 201, Map.of("Location", "/things/1"),
                "{\"name\":\"widget\",\"quantity\":2}", Payload.class.getName(), Instant.now());
    }
}
