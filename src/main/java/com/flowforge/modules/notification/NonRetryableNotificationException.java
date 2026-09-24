package com.flowforge.modules.notification;

/**
 * Marks a message as poison (malformed payload, unknown schema). The Kafka error handler skips retries for
 * this type and routes the record straight to the dead-letter topic.
 */
public class NonRetryableNotificationException extends RuntimeException {

    public NonRetryableNotificationException(String message, Throwable cause) {
        super(message, cause);
    }

    public NonRetryableNotificationException(String message) {
        super(message);
    }
}
