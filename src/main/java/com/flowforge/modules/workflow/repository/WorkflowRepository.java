package com.flowforge.modules.workflow.repository;

import com.flowforge.modules.workflow.domain.Workflow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    Optional<Workflow> findByTenantIdAndId(UUID tenantId, UUID id);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    Page<Workflow> findAllByTenantId(UUID tenantId, Pageable pageable);

    /** Scalar projection: does not instantiate the entity, so it never trips the tenant listener. */
    @Query("select w.tenantId from Workflow w where w.id = :id")
    Optional<UUID> findTenantIdById(@Param("id") UUID id);
}
