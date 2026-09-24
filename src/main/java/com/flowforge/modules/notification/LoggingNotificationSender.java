package com.flowforge.modules.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Default sender that logs; replaced by a real channel adapter in production. */
@Component
@ConditionalOnMissingBean(value = NotificationSender.class, ignored = LoggingNotificationSender.class)
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(UUID tenantId, String channel, String recipient, String subject, Map<String, Object> details) {
        log.info("[NOTIFY] tenant={} channel={} to={} subject='{}' details={}", tenantId, channel, recipient, subject, details);
    }
}
