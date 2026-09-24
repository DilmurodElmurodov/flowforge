package com.flowforge.core.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as idempotent with respect to the {@code X-Idempotency-Key} request header.
 *
 * <p>Semantics enforced by {@link IdempotencyAspect}:
 * <ul>
 *   <li>The first request with a given key executes normally and its HTTP response is cached.</li>
 *   <li>A concurrent request with the same key (while the first is still running) gets {@code 409 Conflict}.</li>
 *   <li>A later request with the same key and the same payload gets the cached response without re-executing.</li>
 *   <li>A later request with the same key but a different payload gets {@code 422 Unprocessable Entity}.</li>
 * </ul>
 * Keys are scoped per tenant, so two tenants can safely use the same key.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** Whether the key header is mandatory. When {@code false}, requests without a key execute normally. */
    boolean required() default true;

    /** Time-to-live of the cached response, ISO-8601 duration. Empty means "use the configured default". */
    String ttl() default "";
}
