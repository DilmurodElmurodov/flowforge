package com.flowforge.modules.identity.service;

import com.flowforge.core.common.exception.ConflictException;
import com.flowforge.core.common.exception.ValidationException;
import com.flowforge.core.tenant.TenantContext;
import com.flowforge.modules.identity.domain.Permission;
import com.flowforge.modules.identity.domain.Role;
import com.flowforge.modules.identity.repository.PermissionRepository;
import com.flowforge.modules.identity.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Role management inside the caller's tenant. */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;

    public RoleService(RoleRepository roles, PermissionRepository permissions) {
        this.roles = roles;
        this.permissions = permissions;
    }

    @Transactional
    public Role create(String name, String description, Set<String> permissionCodes) {
        UUID tenantId = TenantContext.require();
        if (roles.existsByTenantIdAndName(tenantId, name)) {
            throw new ConflictException("ROLE_EXISTS", "Role '" + name + "' already exists");
        }
        List<Permission> found = permissions.findByCodeIn(permissionCodes);
        Set<String> foundCodes = found.stream().map(Permission::getCode).collect(Collectors.toSet());
        List<String> unknown = permissionCodes.stream().filter(c -> !foundCodes.contains(c)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ValidationException("Unknown permission codes", unknown);
        }
        Role role = new Role(name, description);
        role.replacePermissions(new HashSet<>(found));
        return roles.save(role);
    }

    @Transactional(readOnly = true)
    public List<Role> list() {
        return roles.findAllByTenantIdOrderByName(TenantContext.require());
    }
}
