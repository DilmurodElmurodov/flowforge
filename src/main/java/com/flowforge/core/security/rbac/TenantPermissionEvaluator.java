package com.flowforge.core.security.rbac;

import com.flowforge.core.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backs {@code hasPermission(...)} in {@code @PreAuthorize} expressions: the caller must hold the permission
 * code <em>and</em> the target aggregate must belong to the caller's tenant.
 */
@Component
public class TenantPermissionEvaluator implements PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TenantPermissionEvaluator.class);

    private final Map<String, TenantOwnershipResolver> resolversByType;

    public TenantPermissionEvaluator(List<TenantOwnershipResolver> resolvers) {
        this.resolversByType = resolvers.stream()
                .collect(Collectors.toMap(TenantOwnershipResolver::targetType, Function.identity()));
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        return authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.hasAuthority(String.valueOf(permission));
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (!(authentication.getPrincipal() instanceof UserPrincipal principal)
                || !principal.hasAuthority(String.valueOf(permission))) {
            return false;
        }
        TenantOwnershipResolver resolver = resolversByType.get(targetType);
        if (resolver == null) {
            log.error("No TenantOwnershipResolver registered for target type '{}'; denying access", targetType);
            return false;
        }
        UUID id = targetId instanceof UUID uuid ? uuid : UUID.fromString(targetId.toString());
        return resolver.ownerTenantOf(id).filter(principal.getTenantId()::equals).isPresent();
    }
}
