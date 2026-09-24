package com.flowforge.core.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Enables the Hibernate {@code tenantFilter} on the current session around every Spring Data repository call
 * while a tenant is bound. Because a Hibernate filter lives on a {@code Session}, and a session only exists
 * inside a transaction, the aspect opens (or joins) a transaction before enabling the filter. This makes the
 * isolation guarantee independent of whether the calling service remembered to be {@code @Transactional}.
 *
 * <p>When no tenant is bound (system jobs such as the outbox publisher) the filter is deliberately not applied.
 */
@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;

    public TenantFilterAspect(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object applyTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        UUID tenantId = TenantContext.get().orElse(null);
        if (tenantId == null) {
            return joinPoint.proceed();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            enableFilter(tenantId);
            return joinPoint.proceed();
        }
        try {
            return transactionTemplate.execute(status -> {
                enableFilter(tenantId);
                try {
                    return joinPoint.proceed();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new CheckedProceedException(t);
                }
            });
        } catch (CheckedProceedException e) {
            throw e.getCause();
        }
    }

    private void enableFilter(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        Filter filter = session.getEnabledFilter(TenantScopedEntity.TENANT_FILTER);
        if (filter == null) {
            filter = session.enableFilter(TenantScopedEntity.TENANT_FILTER);
        }
        filter.setParameter(TenantScopedEntity.TENANT_PARAM, tenantId);
    }

    /** Carries a checked exception thrown by the join point across the transaction template boundary. */
    private static final class CheckedProceedException extends RuntimeException {
        CheckedProceedException(Throwable cause) {
            super(cause);
        }
    }
}
