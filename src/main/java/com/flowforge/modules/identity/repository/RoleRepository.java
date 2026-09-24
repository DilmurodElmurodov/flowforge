package com.flowforge.modules.identity.repository;

import com.flowforge.modules.identity.domain.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    @EntityGraph(attributePaths = "permissions")
    List<Role> findAllByTenantIdOrderByName(UUID tenantId);
}
