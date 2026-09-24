package com.flowforge.core.tenant;

import java.util.UUID;

/** Marker for entities that belong to exactly one tenant. */
public interface TenantScoped {

    UUID getTenantId();

    void setTenantId(UUID tenantId);
}
