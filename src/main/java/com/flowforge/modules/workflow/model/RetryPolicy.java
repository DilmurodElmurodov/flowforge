package com.flowforge.modules.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-step retry policy applied by the execution engine when a step reports a failure.
 *
 * @param maxAttempts   total attempts including the first one (minimum 1)
 * @param backoffMillis initial delay between attempts; doubled on each retry (capped at 60s)
 */
public record RetryPolicy(int maxAttempts, long backoffMillis) {

    public static final RetryPolicy NONE = new RetryPolicy(1, 0);
    private static final long MAX_BACKOFF_MILLIS = 60_000;

    @JsonCreator
    public RetryPolicy(@JsonProperty("maxAttempts") Integer maxAttempts,
                       @JsonProperty("backoffMillis") Long backoffMillis) {
        this(maxAttempts == null ? 1 : maxAttempts, backoffMillis == null ? 0 : backoffMillis);
    }

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("retry.maxAttempts must be >= 1");
        }
        if (backoffMillis < 0) {
            throw new IllegalArgumentException("retry.backoffMillis must be >= 0");
        }
    }

    /** Exponential backoff for the given (1-based) attempt number. */
    public long delayBeforeAttempt(int attempt) {
        if (attempt <= 1 || backoffMillis == 0) {
            return 0;
        }
        long delay = backoffMillis * (1L << Math.min(attempt - 2, 20));
        return Math.min(delay, MAX_BACKOFF_MILLIS);
    }
}
