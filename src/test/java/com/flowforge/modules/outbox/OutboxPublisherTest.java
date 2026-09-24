package com.flowforge.modules.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock OutboxRepository repository;
    @Mock OutboxEventProcessor processor;

    private SimpleMeterRegistry registry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        OutboxProperties properties = new OutboxProperties(1000, 5000, 10, 5, Duration.ofSeconds(1),
                Duration.ofSeconds(5), Map.of("WorkflowExecution", "topic"));
        publisher = new OutboxPublisher(repository, processor, properties, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesEveryPendingEventInOrderAndCountsOutcomes() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(repository.findPublishableIds(eq(NOW), eq(PageRequest.of(0, 10)))).thenReturn(List.of(a, b, c));
        when(processor.process(a)).thenReturn(OutboxEventProcessor.Outcome.PUBLISHED);
        when(processor.process(b)).thenReturn(OutboxEventProcessor.Outcome.RETRY_SCHEDULED);
        when(processor.process(c)).thenReturn(OutboxEventProcessor.Outcome.PUBLISHED);

        int published = publisher.publishBatch();

        assertThat(published).isEqualTo(2);
        var inOrder = org.mockito.Mockito.inOrder(processor);
        inOrder.verify(processor).process(a);
        inOrder.verify(processor).process(b);
        inOrder.verify(processor).process(c);
        assertThat(registry.get("outbox.events").tag("outcome", "published").counter().count()).isEqualTo(2);
        assertThat(registry.get("outbox.events").tag("outcome", "retry_scheduled").counter().count()).isEqualTo(1);
    }

    @Test
    void optimisticLockFailureIsTreatedAsClaimedByAnotherInstance() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findPublishableIds(any(), any())).thenReturn(List.of(a, b));
        when(processor.process(a)).thenThrow(new OptimisticLockingFailureException("row version changed"));
        when(processor.process(b)).thenReturn(OutboxEventProcessor.Outcome.PUBLISHED);

        int published = publisher.publishBatch();

        assertThat(published).isEqualTo(1);
        verify(processor).process(b);
        assertThat(registry.get("outbox.events").tag("outcome", "skipped").counter().count()).isEqualTo(1);
    }

    @Test
    void unexpectedErrorOnOneEventDoesNotStopTheBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findPublishableIds(any(), any())).thenReturn(List.of(a, b));
        when(processor.process(a)).thenThrow(new IllegalStateException("poison"));
        when(processor.process(b)).thenReturn(OutboxEventProcessor.Outcome.PUBLISHED);

        assertThat(publisher.publishBatch()).isEqualTo(1);
        verify(processor).process(b);
    }

    @Test
    void emptyPollIsANoOp() {
        when(repository.findPublishableIds(any(), any())).thenReturn(List.of());
        assertThat(publisher.publishBatch()).isZero();
        org.mockito.Mockito.verifyNoInteractions(processor);
    }
}
