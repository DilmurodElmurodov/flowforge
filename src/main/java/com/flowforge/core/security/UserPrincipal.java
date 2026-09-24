package com.flowforge.core.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticated identity: a user always belongs to exactly one tenant. Authorities contain both role names
 * ({@code ROLE_*}) and granular permission codes ({@code WORKFLOW_EXECUTE}, ...).
 *
 * <p>The password hash is only populated when the principal is loaded for credential verification; principals
 * rebuilt from a JWT carry {@code null}.
 */
public final class UserPrincipal implements UserDetails {

    private final UUID id;
    private final UUID tenantId;
    private final String username;
    private final String password;
    private final boolean enabled;
    private final Set<GrantedAuthority> authorities;

    public UserPrincipal(UUID id, UUID tenantId, String username, String password, boolean enabled,
                         Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.authorities = Set.copyOf(authorities);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    public boolean hasAuthority(String authority) {
        return authorities.stream().anyMatch(a -> a.getAuthority().equals(authority));
    }

    /** Returns a copy without credentials, safe to keep in the security context. */
    public UserPrincipal eraseCredentials() {
        return new UserPrincipal(id, tenantId, username, null, enabled, authorities);
    }

    @Override
    public String toString() {
        return "UserPrincipal{id=" + id + ", tenantId=" + tenantId + ", username='" + username + "'}";
    }
}
