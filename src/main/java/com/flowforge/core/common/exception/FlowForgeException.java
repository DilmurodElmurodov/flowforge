package com.flowforge.core.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for all domain / application exceptions that carry an HTTP status and a stable error code.
 * The {@code code} is machine-readable and safe to expose to API consumers.
 */
public abstract class FlowForgeException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected FlowForgeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    protected FlowForgeException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
