package com.flowforge.modules.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * Outbox publisher settings ({@code flowforge.outbox.*}).
 *
 * @param pollIntervalMs delay between polling rounds (milliseconds)
 * @param initialDelayMs delay before the first polling round (milliseconds)
 * @param batchSize      maximum events claimed per round
 * @param maxRetries     attempts before an event is marked FAILED
 * @param initialBackoff base delay for exponential retry backoff
 * @param sendTimeout    how long to wait for the broker acknowledgement
 * @param topics         aggregate type -> Kafka topic mapping
 */
@ConfigurationProperties(prefix = "flowforge.outbox")
public record OutboxProperties(
        @DefaultValue("1000") long pollIntervalMs,
        @DefaultValue("5000") long initialDelayMs,
        @DefaultValue("50") int batchSize,
        @DefaultValue("10") int maxRetries,
        @DefaultValue("2s") Duration initialBackoff,
        @DefaultValue("10s") Duration sendTimeout,
        Map<String, String> topics) {

    public OutboxProperties {
        topics = topics == null ? Map.of() : Map.copyOf(topics);
    }

    public String topicFor(String aggregateType) {
        String topic = topics.get(aggregateType);
        if (topic == null) {
            throw new IllegalStateException("No Kafka topic configured for aggregate type '" + aggregateType
                    + "' (flowforge.outbox.topics." + aggregateType + ")");
        }
        return topic;
    }
}
