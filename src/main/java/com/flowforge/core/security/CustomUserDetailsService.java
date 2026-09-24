package com.flowforge.core.security;

import com.flowforge.core.tenant.TenantContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Loads users for password authentication. Usernames are unique per tenant, so the tenant bound to the
 * current request (via the {@code X-Tenant-ID} header) is part of the lookup key.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AccountDirectory accounts;

    public CustomUserDetailsService(AccountDirectory accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UUID tenantId = TenantContext.require();
        return accounts.findByTenantAndUsername(tenantId, username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user '" + username + "'"));
    }
}
