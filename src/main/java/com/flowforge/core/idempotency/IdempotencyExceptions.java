package com.flowforge.core.idempotency;

import com.flowforge.core.common.exception.FlowForgeException;
import org.springframework.http.HttpStatus;

/** Exceptions raised by the idempotency guard, each mapped to a distinct HTTP status. */
public final class IdempotencyExceptions {

    private IdempotencyExceptions() {
    }

    /** 400 - the annotated endpoint requires an idempotency key and none (or an invalid one) was supplied. */
    public static class MissingKeyException extends FlowForgeException {
        public MissingKeyException(String message) {
            super(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", message);
        }
    }

    /** 409 - a request with the same key is currently being processed. */
    public static class InProgressException extends FlowForgeException {
        public InProgressException(String key) {
            super(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_IN_PROGRESS",
                    "A request with idempotency key '" + key + "' is already being processed");
        }
    }

    /** 422 - the key was already used with a different payload. */
    public static class KeyReuseException extends FlowForgeException {
        public KeyReuseException(String key) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key '" + key + "' was already used with a different request payload");
        }
    }
}
