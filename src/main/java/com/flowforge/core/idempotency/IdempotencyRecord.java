package com.flowforge.core.idempotency;

import java.time.Instant;
import java.util.Map;

/**
 * Cached outcome of an idempotent operation.
 *
 * @param fingerprint hash of the request payload; used to detect the same key being reused for a different request
 * @param status      HTTP status code of the original response
 * @param headers     response headers worth replaying (e.g. {@code Location})
 * @param body        response body serialised as JSON ({@code null} for empty bodies)
 * @param bodyType    fully-qualified class name of the body, for deserialisation when the generic type is unknown
 * @param createdAt   when the original response was produced
 */
public record IdempotencyRecord(String fingerprint, int status, Map<String, String> headers, String body,
                                String bodyType, Instant createdAt) {
}
