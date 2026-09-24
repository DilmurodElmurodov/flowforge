package com.flowforge.core.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Configuration of the idempotency guard ({@code flowforge.idempotency.*}). */
@ConfigurationProperties(prefix = "flowforge.idempotency")
public record IdempotencyProperties(
        @DefaultValue("X-Idempotency-Key") String header,
        @DefaultValue("24h") Duration responseTtl,
        @DefaultValue("30s") Duration lockTtl,
        @DefaultValue("128") int maxKeyLength) {
}
