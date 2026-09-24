package com.flowforge.modules.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import com.flowforge.core.common.JsonUtils;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a single outbox event inside its own transaction.
 *
 * <p>Concurrency model: the event is re-read inside the transaction, sent to Kafka synchronously (waiting for the
 * broker acknowledgement), and then marked PUBLISHED. The final flush performs an optimistic-lock check on the
 * {@code version} column; if another publisher instance processed the same row concurrently the update fails,
 * this transaction rolls back and the duplicate delivery is absorbed by idempotent consumers
 * (at-least-once delivery, exactly-once processing).
 */
@Component
public class OutboxEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventProcessor.class);

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_TENANT_ID = "tenantId";
    public static final String HEADER_AGGREGATE_TYPE = "aggregateType";

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Clock clock;

    @Autowired
    public OutboxEventProcessor(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                                OutboxProperties properties) {
        this(repository, kafkaTemplate, properties, Clock.systemUTC());
    }

    OutboxEventProcessor(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                         OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /** Outcome of a processing attempt, for metrics and logging. */
    public enum Outcome { PUBLISHED, SKIPPED, RETRY_SCHEDULED, FAILED }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome process(UUID eventId) {
        Optional<OutboxEvent> maybe = repository.findById(eventId);
        if (maybe.isEmpty() || !maybe.get().isPending()) {
            return Outcome.SKIPPED;
        }
        OutboxEvent event = maybe.get();
        if (event.getNextAttemptAt() != null && event.getNextAttemptAt().isAfter(clock.instant())) {
            return Outcome.SKIPPED;
        }
        try {
            send(event);
            event.markPublished(clock.instant());
            repository.saveAndFlush(event);
            log.debug("Published outbox event {} ({}) for {}#{}", event.getId(), event.getEventType(),
                    event.getAggregateType(), event.getAggregateId());
            return Outcome.PUBLISHED;
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            event.markAttemptFailed(error, clock.instant(), properties.initialBackoff(), properties.maxRetries());
            repository.saveAndFlush(event);
            if (event.getStatus() == OutboxStatus.FAILED) {
                log.error("Outbox event {} permanently FAILED after {} attempts: {}", event.getId(),
                        event.getRetryCount(), error);
                return Outcome.FAILED;
            }
            log.warn("Outbox event {} publish failed (attempt {}), next attempt at {}: {}", event.getId(),
                    event.getRetryCount(), event.getNextAttemptAt(), error);
            return Outcome.RETRY_SCHEDULED;
        }
    }

    private void send(OutboxEvent event) throws ExecutionException, InterruptedException, TimeoutException {
        String topic = properties.topicFor(event.getAggregateType());
        String payload = JsonUtils.toJson(OutboxEventEnvelope.of(event));
        // Key by aggregate id so all events of one execution land on the same partition (ordering guarantee).
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.getAggregateId().toString(), payload);
        record.headers()
                .add(new RecordHeader(HEADER_EVENT_ID, bytes(event.getId().toString())))
                .add(new RecordHeader(HEADER_EVENT_TYPE, bytes(event.getEventType())))
                .add(new RecordHeader(HEADER_AGGREGATE_TYPE, bytes(event.getAggregateType())));
        if (event.getTenantId() != null) {
            record.headers().add(new RecordHeader(HEADER_TENANT_ID, bytes(event.getTenantId().toString())));
        }
        // Blocking on the ack is intentional: the row must only be marked PUBLISHED once the broker has it.
        kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
