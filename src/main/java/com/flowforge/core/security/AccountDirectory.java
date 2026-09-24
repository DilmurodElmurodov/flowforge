package com.flowforge.core.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port through which the security core looks up user accounts. Implemented by the identity module
 * so that {@code core} never depends on {@code modules}.
 */
public interface AccountDirectory {

    Optional<UserPrincipal> findByTenantAndUsername(UUID tenantId, String username);

    Optional<UserPrincipal> findById(UUID tenantId, UUID userId);
}
