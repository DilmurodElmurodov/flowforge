package com.flowforge.core.security.rbac;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the owning tenant of an aggregate for object-level authorisation checks. Each module contributes
 * one bean per aggregate type it exposes through {@code hasPermission(#id, '<targetType>', '<permission>')}.
 */
public interface TenantOwnershipResolver {

    String targetType();

    Optional<UUID> ownerTenantOf(UUID targetId);
}
