package com.flowforge.core.common.exception;

import org.springframework.http.HttpStatus;

/** 422 - the request is well-formed but violates a domain invariant. */
public class BusinessRuleException extends FlowForgeException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
