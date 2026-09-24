package com.flowforge.core.tenant;

/**
 * Raised by {@link TenantEntityListener} when an entity belonging to another tenant is loaded or persisted
 * while a tenant is bound. This is a defence-in-depth control: the Hibernate filter should already exclude
 * such rows, but {@code EntityManager.find} bypasses filters, so the listener is the last line of defence.
 */
public class TenantIsolationViolationException extends RuntimeException {

    public TenantIsolationViolationException(String message) {
        super(message);
    }
}
