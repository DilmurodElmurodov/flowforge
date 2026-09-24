package com.flowforge.core.common.exception;

import org.springframework.http.HttpStatus;

/** 404 - the requested aggregate does not exist (or is not visible to the current tenant). */
public class ResourceNotFoundException extends FlowForgeException {

    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", resource + " with id '" + id + "' was not found");
    }

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }
}
