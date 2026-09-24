package com.flowforge.modules.identity.service;

import com.flowforge.core.common.exception.ConflictException;
import com.flowforge.core.common.exception.ResourceNotFoundException;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.identity.domain.Permission;
import com.flowforge.modules.identity.domain.Role;
import com.flowforge.modules.identity.domain.Tenant;
import com.flowforge.modules.identity.domain.User;
import com.flowforge.modules.identity.repository.PermissionRepository;
import com.flowforge.modules.identity.repository.RoleRepository;
import com.flowforge.modules.identity.repository.TenantRepository;
import com.flowforge.modules.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Tenant provisioning. Creating a tenant is the only operation that legitimately writes into a tenant other
 * than the caller's; it therefore switches the {@link TenantContext} explicitly for the duration of the
 * provisioning steps.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    public static final String ADMIN_ROLE = "ADMIN";

    private final TenantRepository tenants;
    private final RoleRepository roles;
    private final UserRepository users;
    private final PermissionRepository permissions;
    private final PasswordEncoder passwordEncoder;

    public TenantService(TenantRepository tenants, RoleRepository roles, UserRepository users,
                         PermissionRepository permissions, PasswordEncoder passwordEncoder) {
        this.tenants = tenants;
        this.roles = roles;
        this.users = users;
        this.permissions = permissions;
        this.passwordEncoder = passwordEncoder;
    }

    /** Creates a tenant together with an ADMIN role holding every permission and an initial admin user. */
    @Transactional
    public Tenant provision(UUID requestedId, String name, String adminUsername, String adminEmail,
                            String adminPassword) {
        if (tenants.existsByName(name)) {
            throw new ConflictException("TENANT_EXISTS", "Tenant '" + name + "' already exists");
        }
        Tenant tenant = tenants.save(requestedId == null ? new Tenant(name) : new Tenant(requestedId, name));
        TenantContext.runWith(tenant.getId(), () -> {
            Role admin = new Role(ADMIN_ROLE, "Tenant administrator");
            List<Permission> all = permissions.findAll();
            admin.replacePermissions(new HashSet<>(all));
            roles.save(admin);

            User user = new User(adminUsername, adminEmail, passwordEncoder.encode(adminPassword));
            user.assignRole(admin);
            users.save(user);
        });
        log.info("Provisioned tenant '{}' ({}) with admin user '{}'", name, tenant.getId(), adminUsername);
        return tenant;
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID id) {
        return tenants.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tenant", id));
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return tenants.existsById(id);
    }
}
