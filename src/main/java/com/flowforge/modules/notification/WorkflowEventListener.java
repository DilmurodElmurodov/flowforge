package com.flowforge.modules.notification;

import com.flowforge.core.common.JsonUtils;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.outbox.OutboxEventEnvelope;
import com.flowforge.modules.outbox.OutboxEventProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Kafka consumer of workflow execution events.
 *
 * <p>Resilience is configured centrally in {@code KafkaConfig}: a {@code DefaultErrorHandler} with exponential
 * backoff retries transient failures, and terminal failures (or {@link NonRetryableNotificationException}) are
 * forwarded to {@code <topic>.DLT} by a {@code DeadLetterPublishingRecoverer}. The trace context arrives in
 * the record headers and is restored by Spring Kafka's observation support, so logs and spans of the consumer
 * are linked to the originating HTTP request.
 */
@Component
public class WorkflowEventListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final NotificationService notificationService;
    private final ProcessedEventStore processedEvents;

    public WorkflowEventListener(NotificationService notificationService, ProcessedEventStore processedEvents) {
        this.notificationService = notificationService;
        this.processedEvents = processedEvents;
    }

    @KafkaListener(
            topics = "${flowforge.kafka.topics.execution-events}",
            groupId = "${flowforge.kafka.consumer-groups.notification:flowforge-notification}",
            concurrency = "${flowforge.kafka.listener-concurrency:3}")
    public void onExecutionEvent(ConsumerRecord<String, String> record) {
        OutboxEventEnvelope event = parse(record);
        UUID tenantId = event.tenantId() != null ? event.tenantId() : headerUuid(record, OutboxEventProcessor.HEADER_TENANT_ID);
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Duplicate event {} ignored", event.eventId());
            return;
        }
        try {
            TenantContext.runWith(tenantId, () -> notificationService.handle(event));
            log.debug("Processed {} for execution {} (partition {}, offset {})", event.eventType(),
                    event.aggregateId(), record.partition(), record.offset());
        } catch (RuntimeException e) {
            processedEvents.release(event.eventId());
            throw e;    // let the error handler apply backoff / DLT routing
        }
    }

    private static OutboxEventEnvelope parse(ConsumerRecord<String, String> record) {
        try {
            OutboxEventEnvelope envelope = JsonUtils.fromJson(record.value(), OutboxEventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventType() == null) {
                throw new NonRetryableNotificationException("Envelope is missing eventId/eventType");
            }
            return envelope;
        } catch (JsonUtils.JsonException e) {
            throw new NonRetryableNotificationException("Malformed event payload at offset " + record.offset(), e);
        }
    }

    private static UUID headerUuid(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
    }
}
