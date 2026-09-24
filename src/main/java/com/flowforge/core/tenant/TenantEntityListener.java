package com.flowforge.core.tenant;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.util.UUID;

/**
 * JPA lifecycle listener enforcing tenant ownership on {@link TenantScoped} entities.
 * <ul>
 *   <li>{@code @PrePersist}: stamps the current tenant on new entities (or rejects a mismatching one).</li>
 *   <li>{@code @PostLoad}/{@code @PreUpdate}: rejects any entity that belongs to a different tenant than the
 *       one bound to the current context. Loads by primary key bypass Hibernate filters, so this guard is
 *       what makes {@code findById} tenant-safe.</li>
 * </ul>
 * Background processes (outbox publisher, schedulers) run without a bound tenant and are therefore unaffected.
 */
public class TenantEntityListener {

    @PrePersist
    public void assignTenant(Object entity) {
        if (!(entity instanceof TenantScoped scoped)) {
            return;
        }
        UUID current = TenantContext.get().orElse(null);
        if (scoped.getTenantId() == null) {
            if (current == null) {
                throw new TenantResolutionException(
                        "Cannot persist " + entity.getClass().getSimpleName() + " without a tenant");
            }
            scoped.setTenantId(current);
        } else if (current != null && !current.equals(scoped.getTenantId())) {
            throw new TenantIsolationViolationException("Attempt to persist " + entity.getClass().getSimpleName()
                    + " for tenant " + scoped.getTenantId() + " while bound to tenant " + current);
        }
    }

    @PostLoad
    @PreUpdate
    public void verifyTenant(Object entity) {
        if (!(entity instanceof TenantScoped scoped)) {
            return;
        }
        UUID current = TenantContext.get().orElse(null);
        if (current != null && scoped.getTenantId() != null && !current.equals(scoped.getTenantId())) {
            throw new TenantIsolationViolationException(entity.getClass().getSimpleName()
                    + " belongs to tenant " + scoped.getTenantId() + " but context is bound to " + current);
        }
    }
}
