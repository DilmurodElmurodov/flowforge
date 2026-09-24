package com.flowforge.core.tenant;

import com.flowforge.core.common.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;

/**
 * Base class for tenant-owned aggregates.
 *
 * <p>Declares the {@value #TENANT_FILTER} Hibernate filter definition; concrete entities must still add
 * {@code @Filter(name = TENANT_FILTER, condition = TENANT_FILTER_CONDITION)} because Hibernate does not
 * inherit {@code @Filter} from mapped superclasses. {@link TenantFilterAspect} enables the filter for every
 * repository call while a tenant is bound; {@link TenantEntityListener} assigns and verifies the tenant id.
 */
@MappedSuperclass
@EntityListeners(TenantEntityListener.class)
@FilterDef(name = TenantScopedEntity.TENANT_FILTER,
        parameters = @ParamDef(name = TenantScopedEntity.TENANT_PARAM, type = UUID.class))
public abstract class TenantScopedEntity extends AuditEntity implements TenantScoped {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_PARAM = "tenantId";
    public static final String TENANT_FILTER_CONDITION = "tenant_id = :tenantId";

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Override
    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
