package com.flowforge.modules.notification;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Idempotent-consumer guard. Because the outbox guarantees at-least-once delivery, the consumer remembers
 * processed event ids (Redis {@code SETNX} with TTL) and silently drops duplicates.
 */
@Component
public class ProcessedEventStore {

    static final String KEY_PREFIX = "notification:processed:";
    private static final Duration RETENTION = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public ProcessedEventStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns {@code true} if this call claimed the event (first time seen), {@code false} on a duplicate. */
    public boolean markProcessed(UUID eventId) {
        Boolean first = redis.opsForValue().setIfAbsent(KEY_PREFIX + eventId, "1", RETENTION);
        return Boolean.TRUE.equals(first);
    }

    /** Releases a claim when processing failed so the retry (or DLT replay) can process it again. */
    public void release(UUID eventId) {
        redis.delete(KEY_PREFIX + eventId);
    }
}
