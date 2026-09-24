package com.flowforge.modules.execution.service;

import com.flowforge.modules.execution.engine.ExecutionContext;
import com.flowforge.modules.workflow.model.WorkflowDefinition;

import java.time.Instant;
import java.util.UUID;

/** Detached view of an execution handed to the engine so it never holds managed entities across steps. */
public record ExecutionSnapshot(UUID executionId, UUID tenantId, UUID workflowId, UUID workflowVersionId,
                                String workflowName, WorkflowDefinition definition, int currentStepIndex,
                                ExecutionContext context, Instant startedAt) {
}
