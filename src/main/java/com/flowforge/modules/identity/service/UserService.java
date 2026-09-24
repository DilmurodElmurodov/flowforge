package com.flowforge.modules.identity.service;

import com.flowforge.core.common.exception.ConflictException;
import com.flowforge.core.common.exception.ResourceNotFoundException;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.identity.domain.Role;
import com.flowforge.modules.identity.domain.User;
import com.flowforge.modules.identity.repository.RoleRepository;
import com.flowforge.modules.identity.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/** User management inside the caller's tenant. */
@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User create(String username, String email, String rawPassword, Set<String> roleNames) {
        UUID tenantId = TenantContext.require();
        if (users.existsByTenantIdAndUsername(tenantId, username)) {
            throw new ConflictException("USER_EXISTS", "Username '" + username + "' is already taken");
        }
        if (users.existsByTenantIdAndEmail(tenantId, email)) {
            throw new ConflictException("USER_EXISTS", "Email '" + email + "' is already registered");
        }
        User user = new User(username, email, passwordEncoder.encode(rawPassword));
        for (String roleName : roleNames) {
            Role role = roles.findByTenantIdAndName(tenantId, roleName)
                    .orElseThrow(() -> new ResourceNotFoundException("Role '" + roleName + "' was not found"));
            user.assignRole(role);
        }
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findWithAuthoritiesByTenantIdAndId(TenantContext.require(), id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
