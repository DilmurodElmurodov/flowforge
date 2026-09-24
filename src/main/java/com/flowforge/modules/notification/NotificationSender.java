package com.flowforge.modules.notification;

import java.util.Map;
import java.util.UUID;

/** Port for delivering notifications (e-mail, chat, push, ...). */
public interface NotificationSender {

    void send(UUID tenantId, String channel, String recipient, String subject, Map<String, Object> details);
}
