package com.flowforge.modules.execution.engine.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default gateway that only logs. Replaced in production by a real provider bean. */
@Component
@ConditionalOnMissingBean(value = EmailGateway.class, ignored = LoggingEmailGateway.class)
public class LoggingEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailGateway.class);

    @Override
    public String send(String to, String subject, String body) {
        String messageId = UUID.randomUUID().toString();
        log.info("[EMAIL] to={} subject='{}' messageId={} bodyLength={}", to, subject, messageId,
                body == null ? 0 : body.length());
        return messageId;
    }
}
