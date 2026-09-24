package com.flowforge.modules.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();

    @Mock OutboxRepository repository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    private OutboxEventProcessor processor;
    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties(1000, 5000, 10, 3, Duration.ofSeconds(2),
                Duration.ofSeconds(5), Map.of("WorkflowExecution", "flowforge.workflow-execution.events"));
        processor = new OutboxEventProcessor(repository, kafkaTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        event = new OutboxEvent(TENANT, "WorkflowExecution", UUID.randomUUID(), "EXECUTION_COMPLETED",
                Map.of("status", "COMPLETED"), NOW.minusSeconds(1));
    }

    private void givenStoredEvent() {
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));
    }

    @Test
    void sendsEnvelopeWithHeadersAndMarksPublishedAfterAck() {
        givenStoredEvent();
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class)));

        OutboxEventProcessor.Outcome outcome = processor.process(event.getId());

        assertThat(outcome).isEqualTo(OutboxEventProcessor.Outcome.PUBLISHED);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo("flowforge.workflow-execution.events");
        assertThat(record.getValue().key()).isEqualTo(event.getAggregateId().toString());
        assertThat(record.getValue().value()).contains("\"eventType\":\"EXECUTION_COMPLETED\"")
                .contains("\"eventId\":\"" + event.getId() + "\"");
        assertThat(new String(record.getValue().headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                .isEqualTo(event.getId().toString());
        assertThat(new String(record.getValue().headers().lastHeader("tenantId").value(), StandardCharsets.UTF_8))
                .isEqualTo(TENANT.toString());
    }

    @Test
    void brokerFailureSchedulesRetryWithBackoff() {
        givenStoredEvent();
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        OutboxEventProcessor.Outcome outcome = processor.process(event.getId());

        assertThat(outcome).isEqualTo(OutboxEventProcessor.Outcome.RETRY_SCHEDULED);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(event.getLastError()).contains("broker unavailable");
        verify(repository).saveAndFlush(event);
    }

    @Test
    void exhaustedRetriesMarkEventFailed() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        // Two earlier attempts already failed long enough ago that the event is eligible again.
        OutboxEvent eligible = new OutboxEvent(TENANT, "WorkflowExecution", event.getAggregateId(), "X", Map.of(), NOW);
        eligible.markAttemptFailed("down", NOW.minusSeconds(100), Duration.ofSeconds(1), 3);
        eligible.markAttemptFailed("down", NOW.minusSeconds(100), Duration.ofSeconds(1), 3);
        when(repository.findById(eligible.getId())).thenReturn(Optional.of(eligible));

        OutboxEventProcessor.Outcome outcome = processor.process(eligible.getId());

        assertThat(outcome).isEqualTo(OutboxEventProcessor.Outcome.FAILED);
        assertThat(eligible.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(eligible.getRetryCount()).isEqualTo(3);
        assertThat(eligible.getNextAttemptAt()).isNull();
    }

    @Test
    void alreadyPublishedEventIsSkipped() {
        givenStoredEvent();
        event.markPublished(NOW);

        assertThat(processor.process(event.getId())).isEqualTo(OutboxEventProcessor.Outcome.SKIPPED);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void eventScheduledInTheFutureIsSkipped() {
        givenStoredEvent();
        event.markAttemptFailed("x", NOW, Duration.ofMinutes(5), 10);

        assertThat(processor.process(event.getId())).isEqualTo(OutboxEventProcessor.Outcome.SKIPPED);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }
}
