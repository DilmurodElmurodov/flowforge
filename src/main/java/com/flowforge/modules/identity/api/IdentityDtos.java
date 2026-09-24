package com.flowforge.modules.identity.api;

import com.flowforge.modules.identity.domain.Permission;
import com.flowforge.modules.identity.domain.Role;
import com.flowforge.modules.identity.domain.Tenant;
import com.flowforge.modules.identity.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** API contracts of the identity module. */
public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record ProvisionTenantRequest(
            UUID tenantId,
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 100) String adminUsername,
            @NotBlank @Email String adminEmail,
            @NotBlank @Size(min = 12, max = 128) String adminPassword) {
    }

    public record TenantResponse(UUID id, String name, String status, Instant createdAt) {
        public static TenantResponse from(Tenant tenant) {
            return new TenantResponse(tenant.getId(), tenant.getName(), tenant.getStatus().name(), tenant.getCreatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotEmpty Set<String> roles) {
    }

    public record UserResponse(UUID id, UUID tenantId, String username, String email, String status,
                               Set<String> roles, Set<String> permissions) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getTenantId(), user.getUsername(), user.getEmail(),
                    user.getStatus().name(),
                    user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()),
                    user.getRoles().stream().flatMap(r -> r.getPermissions().stream())
                            .map(Permission::getCode).collect(Collectors.toSet()));
        }
    }

    public record CreateRoleRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
            @NotEmpty Set<String> permissions) {
    }

    public record RoleResponse(UUID id, String name, String description, Set<String> permissions) {
        public static RoleResponse from(Role role) {
            return new RoleResponse(role.getId(), role.getName(), role.getDescription(),
                    role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet()));
        }
    }
}
