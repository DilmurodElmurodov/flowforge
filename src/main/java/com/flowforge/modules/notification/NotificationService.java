package com.flowforge.modules.notification;

import com.flowforge.modules.execution.service.ExecutionEventType;
import com.flowforge.modules.outbox.OutboxEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/** Maps execution integration events to user-facing notifications. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String CHANNEL_EMAIL = "email";

    private final NotificationSender sender;

    public NotificationService(NotificationSender sender) {
        this.sender = sender;
    }

    public void handle(OutboxEventEnvelope event) {
        Map<String, Object> payload = event.payload();
        String initiator = Objects.toString(payload.get("initiatedBy"), "system");
        switch (event.eventType()) {
            case ExecutionEventType.WAITING_APPROVAL -> sender.send(event.tenantId(), CHANNEL_EMAIL,
                    "role:" + payload.get("approverRole"),
                    "Approval required for execution " + event.aggregateId(), payload);
            case ExecutionEventType.COMPLETED -> sender.send(event.tenantId(), CHANNEL_EMAIL, initiator,
                    "Execution " + event.aggregateId() + " completed", payload);
            case ExecutionEventType.FAILED -> sender.send(event.tenantId(), CHANNEL_EMAIL, initiator,
                    "Execution " + event.aggregateId() + " FAILED: " + payload.get("reason"), payload);
            default -> log.debug("No notification configured for event type {}", event.eventType());
        }
    }
}
