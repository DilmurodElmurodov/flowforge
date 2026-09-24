package com.flowforge.core.tenant;

import com.flowforge.core.common.exception.FlowForgeException;
import org.springframework.http.HttpStatus;

/** 400 - the tenant could not be determined for the current request. */
public class TenantResolutionException extends FlowForgeException {

    public TenantResolutionException(String message) {
        super(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", message);
    }
}
