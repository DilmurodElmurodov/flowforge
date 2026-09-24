package com.flowforge.modules.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Background relay: polls PENDING outbox events and hands each one to {@link OutboxEventProcessor}.
 *
 * <p>Multiple application instances may run this scheduler concurrently. Optimistic locking in the processor
 * guarantees that a row is never marked PUBLISHED twice; a lost race simply results in a duplicate message that
 * consumers deduplicate by event id.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final OutboxEventProcessor processor;
    private final OutboxProperties properties;
    private final Map<OutboxEventProcessor.Outcome, Counter> counters = new EnumMap<>(OutboxEventProcessor.Outcome.class);
    private final Clock clock;

    @Autowired
    public OutboxPublisher(OutboxRepository repository, OutboxEventProcessor processor, OutboxProperties properties,
                           MeterRegistry meterRegistry) {
        this(repository, processor, properties, meterRegistry, Clock.systemUTC());
    }

    OutboxPublisher(OutboxRepository repository, OutboxEventProcessor processor, OutboxProperties properties,
                    MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
        for (OutboxEventProcessor.Outcome outcome : OutboxEventProcessor.Outcome.values()) {
            counters.put(outcome, Counter.builder("outbox.events")
                    .description("Outbox events processed by outcome")
                    .tag("outcome", outcome.name().toLowerCase())
                    .register(meterRegistry));
        }
    }

    @Scheduled(fixedDelayString = "${flowforge.outbox.poll-interval-ms:1000}",
            initialDelayString = "${flowforge.outbox.initial-delay-ms:5000}")
    public void publishPending() {
        int published = publishBatch();
        if (published > 0) {
            log.debug("Outbox round published {} event(s)", published);
        }
    }

    /** Processes one batch and returns how many events were published. Exposed for tests and manual triggers. */
    public int publishBatch() {
        List<UUID> ids = repository.findPublishableIds(clock.instant(), PageRequest.of(0, properties.batchSize()));
        int published = 0;
        for (UUID id : ids) {
            try {
                OutboxEventProcessor.Outcome outcome = processor.process(id);
                counters.get(outcome).increment();
                if (outcome == OutboxEventProcessor.Outcome.PUBLISHED) {
                    published++;
                }
            } catch (OptimisticLockingFailureException e) {
                // Another instance won the race for this row; nothing to do.
                counters.get(OutboxEventProcessor.Outcome.SKIPPED).increment();
                log.debug("Outbox event {} was claimed by another publisher instance", id);
            } catch (RuntimeException e) {
                // Never let one poisoned row stop the whole relay.
                log.error("Unexpected error while processing outbox event {}", id, e);
            }
        }
        return published;
    }
}
