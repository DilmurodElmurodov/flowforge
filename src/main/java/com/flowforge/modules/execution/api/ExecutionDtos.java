package com.flowforge.modules.execution.api;

import com.flowforge.modules.execution.domain.ExecutionStepRecord;
import com.flowforge.modules.execution.domain.WorkflowExecution;
import com.flowforge.modules.execution.service.ExecutionService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API contracts of the execution module. */
public final class ExecutionDtos {

    private ExecutionDtos() {
    }

    public record StartExecutionRequest(
            @NotNull UUID workflowId,
            @Positive Integer versionNumber,
            Map<String, Object> input) {
    }

    public record ApprovalRequest(
            @NotNull ExecutionService.ApprovalDecision decision,
            @Size(max = 1000) String comment) {
    }

    public record ExecutionResponse(UUID id, UUID workflowId, UUID workflowVersionId, String status,
                                    int currentStepIndex, String currentStepId, Map<String, Object> context,
                                    String failureReason, Instant createdAt, Instant startedAt,
                                    Instant completedAt, Instant updatedAt) {
        public static ExecutionResponse from(WorkflowExecution execution) {
            return new ExecutionResponse(execution.getId(), execution.getWorkflowId(), execution.getWorkflowVersionId(),
                    execution.getStatus().name(), execution.getCurrentStepIndex(), execution.getCurrentStepId(),
                    execution.getContextData(), execution.getFailureReason(), execution.getCreatedAt(),
                    execution.getStartedAt(), execution.getCompletedAt(), execution.getUpdatedAt());
        }
    }

    public record StepRecordResponse(int stepIndex, String stepId, String stepType, String status, String outcome,
                                     int attempts, Map<String, Object> output, String message, Instant startedAt,
                                     Instant finishedAt) {
        public static StepRecordResponse from(ExecutionStepRecord record) {
            return new StepRecordResponse(record.getStepIndex(), record.getStepId(), record.getStepType().name(),
                    record.getStatus(), record.getOutcome(), record.getAttempts(), record.getOutput(),
                    record.getMessage(), record.getStartedAt(), record.getFinishedAt());
        }
    }
}
