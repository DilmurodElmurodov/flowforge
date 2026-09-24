package com.flowforge.modules.execution.engine.executor;

/** Outbound e-mail port. Swap the implementation (SES, SendGrid, SMTP) without touching the executor. */
public interface EmailGateway {

    /** Sends the message and returns a provider message id. */
    String send(String to, String subject, String body);
}
