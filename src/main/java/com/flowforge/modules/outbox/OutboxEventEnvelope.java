package com.flowforge.modules.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Wire format of every message published from the outbox. Consumers deduplicate on {@code eventId}
 * (the outbox row id), which makes at-least-once delivery safe.
 */
public record OutboxEventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        UUID tenantId,
        Instant occurredAt,
        Map<String, Object> payload) {

    public static OutboxEventEnvelope of(OutboxEvent event) {
        return new OutboxEventEnvelope(event.getId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getTenantId(), event.getCreatedAt(), event.getPayload());
    }
}
