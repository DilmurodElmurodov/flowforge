package com.flowforge.core.tenant;

import io.micrometer.context.ThreadLocalAccessor;

import java.util.UUID;

/**
 * Exposes {@link TenantContext} to Micrometer's context-propagation library so the tenant travels together with
 * the trace context across {@code ContextPropagatingTaskDecorator}-decorated executors and
 * {@code ContextSnapshot} captures.
 */
public class TenantThreadLocalAccessor implements ThreadLocalAccessor<UUID> {

    public static final String KEY = "flowforge.tenant";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public UUID getValue() {
        return TenantContext.get().orElse(null);
    }

    @Override
    public void setValue(UUID value) {
        TenantContext.set(value);
    }

    @Override
    public void setValue() {
        TenantContext.clear();
    }
}
