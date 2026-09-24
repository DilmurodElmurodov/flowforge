package com.flowforge.modules.outbox;

/** Delivery state of an outbox event. */
public enum OutboxStatus {
    /** Persisted with the business change; waiting to be published. */
    PENDING,
    /** Acknowledged by the message broker. */
    PUBLISHED,
    /** Retries exhausted; requires operator intervention (or a replay tool). */
    FAILED
}
