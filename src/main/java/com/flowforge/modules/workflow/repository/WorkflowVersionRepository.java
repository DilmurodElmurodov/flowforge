package com.flowforge.modules.workflow.repository;

import com.flowforge.modules.workflow.domain.WorkflowVersion;
import com.flowforge.modules.workflow.domain.WorkflowVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

    Optional<WorkflowVersion> findByWorkflowIdAndVersionNumber(UUID workflowId, int versionNumber);

    Optional<WorkflowVersion> findFirstByWorkflowIdAndStatusOrderByVersionNumberDesc(UUID workflowId, WorkflowVersionStatus status);

    List<WorkflowVersion> findByWorkflowIdAndStatus(UUID workflowId, WorkflowVersionStatus status);

    List<WorkflowVersion> findByWorkflowIdOrderByVersionNumberDesc(UUID workflowId);
}
