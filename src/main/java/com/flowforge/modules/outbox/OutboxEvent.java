package com.flowforge.modules.outbox;

import com.flowforge.core.common.BaseEntity;
import com.flowforge.core.common.JsonDocument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox row. Written in the same transaction as the aggregate change it describes and published
 * asynchronously by {@link OutboxPublisher}. The inherited {@code @Version} column provides the optimistic lock
 * that lets several publisher instances poll the same table safely.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseEntity {

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID tenantId, String aggregateType, UUID aggregateId, String eventType,
                       Map<String, Object> payload, Instant createdAt) {
        this.tenantId = tenantId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = JsonDocument.write(payload);
        this.createdAt = createdAt;
    }

    public void markPublished(Instant now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /** Records a failed attempt and schedules the next one with exponential backoff, or gives up. */
    public void markAttemptFailed(String error, Instant now, Duration initialBackoff, int maxRetries) {
        this.retryCount += 1;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        if (retryCount >= maxRetries) {
            this.status = OutboxStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            long multiplier = 1L << Math.min(retryCount - 1, 10);
            this.nextAttemptAt = now.plus(initialBackoff.multipliedBy(multiplier));
        }
    }

    public boolean isPending() {
        return status == OutboxStatus.PENDING;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return JsonDocument.read(payload);
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }
}
