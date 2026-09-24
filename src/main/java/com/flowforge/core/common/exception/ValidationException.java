package com.flowforge.core.common.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

/** 400 - the request payload is structurally invalid (e.g. a malformed workflow definition). */
public class ValidationException extends FlowForgeException {

    private final List<String> violations;

    public ValidationException(String message, List<String> violations) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
        this.violations = List.copyOf(violations);
    }

    public ValidationException(String message) {
        this(message, List.of());
    }

    public List<String> getViolations() {
        return violations;
    }
}
