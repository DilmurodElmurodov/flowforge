package com.flowforge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Kafka topology settings ({@code flowforge.kafka.*}). */
@ConfigurationProperties(prefix = "flowforge.kafka")
public record KafkaTopicProperties(
        @DefaultValue Topics topics,
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue Retry retry) {

    public record Topics(@DefaultValue("flowforge.workflow-execution.events") String executionEvents) {
        public String executionEventsDlt() {
            return executionEvents + ".DLT";
        }
    }

    /** Consumer retry policy applied by the {@code DefaultErrorHandler}. */
    public record Retry(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1000") long initialIntervalMs,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("30000") long maxIntervalMs) {
    }
}
