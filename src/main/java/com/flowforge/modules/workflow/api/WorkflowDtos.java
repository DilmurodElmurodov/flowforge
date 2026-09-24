package com.flowforge.modules.workflow.api;

import com.flowforge.modules.workflow.domain.Workflow;
import com.flowforge.modules.workflow.domain.WorkflowVersion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API contracts of the workflow module. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record CreateWorkflowRequest(@NotBlank @Size(max = 150) String name, @Size(max = 1000) String description) {
    }

    public record WorkflowResponse(UUID id, String name, String description, String status, int latestVersion,
                                   Instant createdAt, Instant updatedAt) {
        public static WorkflowResponse from(Workflow workflow) {
            return new WorkflowResponse(workflow.getId(), workflow.getName(), workflow.getDescription(),
                    workflow.getStatus().name(), workflow.getLatestVersion(), workflow.getCreatedAt(),
                    workflow.getUpdatedAt());
        }
    }

    /** The request body <em>is</em> the declarative definition document. */
    public record CreateVersionRequest(@NotEmpty Map<String, Object> definition) {
    }

    public record WorkflowVersionResponse(UUID id, UUID workflowId, int versionNumber, String status,
                                          Map<String, Object> definition, Instant createdAt, Instant publishedAt) {
        public static WorkflowVersionResponse from(WorkflowVersion version) {
            return new WorkflowVersionResponse(version.getId(), version.getWorkflowId(), version.getVersionNumber(),
                    version.getStatus().name(), version.getDefinition(), version.getCreatedAt(), version.getPublishedAt());
        }
    }
}
