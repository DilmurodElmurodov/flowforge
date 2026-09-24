package com.flowforge.core.common.exception;

import org.springframework.http.HttpStatus;

/** 409 - the request conflicts with the current state of the resource (duplicate, illegal transition, ...). */
public class ConflictException extends FlowForgeException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
