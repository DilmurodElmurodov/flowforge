package com.flowforge.modules.execution.repository;

import com.flowforge.modules.execution.domain.ExecutionStatus;
import com.flowforge.modules.execution.domain.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    Optional<WorkflowExecution> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Page<WorkflowExecution> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    long countByTenantIdAndWorkflowId(UUID tenantId, UUID workflowId);

    @Query("select e.tenantId from WorkflowExecution e where e.id = :id")
    Optional<UUID> findTenantIdById(@Param("id") UUID id);

    /** Executions that were committed but never picked up by the engine (e.g. crash right after commit). */
    @Query("""
            select e from WorkflowExecution e
            where e.status = :status and e.createdAt < :before
            order by e.createdAt asc
            """)
    List<WorkflowExecution> findStalled(@Param("status") ExecutionStatus status, @Param("before") Instant before,
                                        Pageable pageable);
}
