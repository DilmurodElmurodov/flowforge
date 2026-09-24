package com.flowforge.core.tenant;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Holds the tenant of the current unit of work in a {@link ThreadLocal}.
 *
 * <p>The context is populated by {@link TenantFilter} for HTTP requests, by the Kafka consumers for inbound
 * messages, and propagated into async executors through {@link TenantThreadLocalAccessor}
 * (Micrometer context-propagation). The tenant id is mirrored into the logging {@link MDC} so every log line
 * can be attributed to a tenant.
 */
public final class TenantContext {

    public static final String MDC_KEY = "tenantId";

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            clear();
            return;
        }
        CURRENT.set(tenantId);
        MDC.put(MDC_KEY, tenantId.toString());
    }

    public static Optional<UUID> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    /** Returns the current tenant or fails loudly; use in code paths that must never run tenant-less. */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new TenantResolutionException("No tenant bound to the current execution context");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    /** Executes {@code action} with {@code tenantId} bound, restoring the previous tenant afterwards. */
    public static <T> T runWith(UUID tenantId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    public static void runWith(UUID tenantId, Runnable action) {
        runWith(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
