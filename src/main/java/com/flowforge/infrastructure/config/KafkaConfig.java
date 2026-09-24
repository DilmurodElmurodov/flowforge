package com.flowforge.infrastructure.config;

import com.flowforge.modules.notification.NonRetryableNotificationException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Kafka topology and consumer resilience.
 * <ul>
 *   <li>Topics (and their {@code .DLT} twins with identical partition counts) are declared so the broker
 *       creates them on start-up.</li>
 *   <li>The {@link DefaultErrorHandler} bean is picked up by Spring Boot's listener-container factory: failed
 *       records are retried with exponential backoff and finally forwarded to {@code <topic>.DLT}, preserving
 *       the original partition so ordering semantics survive.</li>
 *   <li>Producer/consumer observation is enabled through {@code spring.kafka.*.observation-enabled} so trace
 *       context travels in record headers.</li>
 * </ul>
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public NewTopic executionEventsTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name(properties.topics().executionEvents())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    @Bean
    public NewTopic executionEventsDeadLetterTopic(KafkaTopicProperties properties) {
        return TopicBuilder.name(properties.topics().executionEventsDlt())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations,
                                                 KafkaTopicProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        KafkaTopicProperties.Retry retry = properties.retry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts());
        backOff.setInitialInterval(retry.initialIntervalMs());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxIntervalMs());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NonRetryableNotificationException.class, DeserializationException.class,
                IllegalArgumentException.class);
        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("Retrying record topic={} partition={} offset={} attempt={} cause={}", record.topic(),
                        record.partition(), record.offset(), attempt, ex.getMessage()));
        return handler;
    }
}
