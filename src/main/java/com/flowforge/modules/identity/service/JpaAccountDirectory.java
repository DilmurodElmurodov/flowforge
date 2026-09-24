package com.flowforge.modules.identity.service;

import com.flowforge.core.security.AccountDirectory;
import com.flowforge.core.security.UserPrincipal;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.identity.domain.Permission;
import com.flowforge.modules.identity.domain.Role;
import com.flowforge.modules.identity.domain.User;
import com.flowforge.modules.identity.repository.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Adapter exposing identity aggregates to the security core as {@link UserPrincipal}s. */
@Component
public class JpaAccountDirectory implements AccountDirectory {

    private final UserRepository users;

    public JpaAccountDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserPrincipal> findByTenantAndUsername(UUID tenantId, String username) {
        return TenantContext.runWith(tenantId,
                () -> users.findWithAuthoritiesByTenantIdAndUsername(tenantId, username).map(JpaAccountDirectory::toPrincipal));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserPrincipal> findById(UUID tenantId, UUID userId) {
        return TenantContext.runWith(tenantId,
                () -> users.findWithAuthoritiesByTenantIdAndId(tenantId, userId).map(JpaAccountDirectory::toPrincipal));
    }

    static UserPrincipal toPrincipal(User user) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        for (Role role : user.getRoles()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
            for (Permission permission : role.getPermissions()) {
                authorities.add(new SimpleGrantedAuthority(permission.getCode()));
            }
        }
        return new UserPrincipal(user.getId(), user.getTenantId(), user.getUsername(), user.getPasswordHash(),
                user.isActive(), authorities);
    }
}
