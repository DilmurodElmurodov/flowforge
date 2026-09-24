package com.flowforge.integration;

import com.flowforge.modules.notification.NotificationSender;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures notifications produced by the Kafka consumer so tests can assert on the end-to-end flow. */
public class RecordingNotificationSender implements NotificationSender {

    public record Notification(UUID tenantId, String channel, String recipient, String subject, Map<String, Object> details) {
    }

    private final List<Notification> notifications = new CopyOnWriteArrayList<>();

    @Override
    public void send(UUID tenantId, String channel, String recipient, String subject, Map<String, Object> details) {
        notifications.add(new Notification(tenantId, channel, recipient, subject, details));
    }

    public List<Notification> all() {
        return List.copyOf(notifications);
    }

    public List<Notification> forExecution(UUID executionId) {
        return notifications.stream()
                .filter(n -> executionId.toString().equals(String.valueOf(n.details().get("executionId"))))
                .toList();
    }
}
