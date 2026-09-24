package com.flowforge.modules.execution.service;

import org.springframework.beans.factory.annotation.Autowired;
import com.flowforge.core.common.exception.ConflictException;
import com.flowforge.core.common.exception.ResourceNotFoundException;
import com.flowforge.modules.execution.domain.ExecutionStatus;
import com.flowforge.modules.execution.domain.ExecutionStepRecord;
import com.flowforge.modules.execution.domain.WorkflowExecution;
import com.flowforge.modules.execution.engine.ExecutionContext;
import com.flowforge.modules.execution.engine.ExecutionResult;
import com.flowforge.modules.execution.repository.ExecutionStepRecordRepository;
import com.flowforge.modules.execution.repository.WorkflowExecutionRepository;
import com.flowforge.modules.outbox.OutboxService;
import com.flowforge.modules.workflow.domain.WorkflowVersion;
import com.flowforge.modules.workflow.model.WorkflowDefinition;
import com.flowforge.modules.workflow.service.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional boundary of the execution runtime. Every method loads the aggregate, applies exactly one state
 * change, records the step audit row and appends the matching outbox event <strong>in the same transaction</strong>.
 * That single commit is what makes the outbox reliable: either both the state and the event are durable, or
 * neither is.
 */
@Service
public class ExecutionStateManager {

    private final WorkflowExecutionRepository executions;
    private final ExecutionStepRecordRepository stepRecords;
    private final WorkflowService workflowService;
    private final OutboxService outbox;
    private final Clock clock;

    @Autowired
    public ExecutionStateManager(WorkflowExecutionRepository executions, ExecutionStepRecordRepository stepRecords,
                                 WorkflowService workflowService, OutboxService outbox) {
        this(executions, stepRecords, workflowService, outbox, Clock.systemUTC());
    }

    ExecutionStateManager(WorkflowExecutionRepository executions, ExecutionStepRecordRepository stepRecords,
                          WorkflowService workflowService, OutboxService outbox, Clock clock) {
        this.executions = executions;
        this.stepRecords = stepRecords;
        this.workflowService = workflowService;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** PENDING -> IN_PROGRESS. */
    @Transactional
    public ExecutionSnapshot begin(UUID executionId) {
        WorkflowExecution execution = load(executionId);
        if (execution.getStatus() != ExecutionStatus.PENDING) {
            throw new ConflictException("EXECUTION_NOT_PENDING",
                    "Execution " + executionId + " is " + execution.getStatus() + " and cannot be started");
        }
        execution.start(clock.instant());
        executions.save(execution);
        ExecutionSnapshot snapshot = snapshotOf(execution);
        emit(execution, ExecutionEventType.STARTED, Map.of("stepId", firstStepId(snapshot)));
        return snapshot;
    }

    /** Read-only snapshot of an IN_PROGRESS execution (used when resuming after an approval). */
    @Transactional(readOnly = true)
    public ExecutionSnapshot snapshot(UUID executionId) {
        WorkflowExecution execution = load(executionId);
        if (execution.getStatus() != ExecutionStatus.IN_PROGRESS) {
            throw new ConflictException("EXECUTION_NOT_IN_PROGRESS",
                    "Execution " + executionId + " is " + execution.getStatus());
        }
        return snapshotOf(execution);
    }

    /** Step finished; move the pointer to the next step. */
    @Transactional
    public void stepCompleted(UUID executionId, StepAttempt attempt, ExecutionContext context, int nextIndex,
                              String nextStepId) {
        WorkflowExecution execution = load(executionId);
        execution.advanceTo(nextIndex, nextStepId, context.toPersistent());
        executions.save(execution);
        record(execution, attempt, "COMPLETED");
        Map<String, Object> payload = stepPayload(attempt);
        payload.put("nextStepId", nextStepId);
        emit(execution, ExecutionEventType.STEP_COMPLETED, payload);
    }

    /** IN_PROGRESS -> WAITING (human step). */
    @Transactional
    public void waiting(UUID executionId, StepAttempt attempt, ExecutionContext context) {
        WorkflowExecution execution = load(executionId);
        execution.park(context.toPersistent());
        executions.save(execution);
        record(execution, attempt, "WAITING");
        Map<String, Object> payload = stepPayload(attempt);
        payload.put("approverRole", attempt.step().config().get("approverRole"));
        emit(execution, ExecutionEventType.WAITING_APPROVAL, payload);
    }

    /** IN_PROGRESS -> COMPLETED. */
    @Transactional
    public void completed(UUID executionId, StepAttempt lastAttempt, ExecutionContext context) {
        WorkflowExecution execution = load(executionId);
        execution.complete(clock.instant(), context.toPersistent());
        executions.save(execution);
        record(execution, lastAttempt, "COMPLETED");
        Map<String, Object> payload = stepPayload(lastAttempt);
        payload.put("completedAt", execution.getCompletedAt());
        emit(execution, ExecutionEventType.COMPLETED, payload);
    }

    /** any -> FAILED. {@code attempt} may be null for infrastructure failures outside a step. */
    @Transactional
    public void failed(UUID executionId, StepAttempt attempt, ExecutionContext context, String reason) {
        WorkflowExecution execution = load(executionId);
        if (execution.getStatus().isTerminal()) {
            return;
        }
        execution.fail(clock.instant(), reason, context == null ? null : context.toPersistent());
        executions.save(execution);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (attempt != null) {
            record(execution, attempt, "FAILED");
            payload = stepPayload(attempt);
        }
        payload.put("reason", reason);
        emit(execution, ExecutionEventType.FAILED, payload);
    }

    // ---------------------------------------------------------------------------------------------------------

    private WorkflowExecution load(UUID executionId) {
        return executions.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowExecution", executionId));
    }

    private ExecutionSnapshot snapshotOf(WorkflowExecution execution) {
        WorkflowVersion version = workflowService.loadVersion(execution.getWorkflowVersionId());
        WorkflowDefinition definition = workflowService.definitionOf(version);
        String workflowName = definition.name() != null ? definition.name()
                : workflowService.get(execution.getWorkflowId()).getName();
        ExecutionContext context = ExecutionContext.fromPersistent(execution.getId(), execution.getTenantId(),
                execution.getContextData());
        Instant startedAt = execution.getStartedAt() != null ? execution.getStartedAt() : clock.instant();
        return new ExecutionSnapshot(execution.getId(), execution.getTenantId(), execution.getWorkflowId(),
                execution.getWorkflowVersionId(), workflowName, definition, execution.getCurrentStepIndex(),
                context, startedAt);
    }

    private void record(WorkflowExecution execution, StepAttempt attempt, String status) {
        ExecutionResult result = attempt.result();
        stepRecords.save(new ExecutionStepRecord(execution.getId(), attempt.stepIndex(), attempt.step().id(),
                attempt.step().type(), status, result.outcome(), attempt.attempts(), result.output(),
                result.message(), attempt.startedAt(), attempt.finishedAt()));
    }

    private void emit(WorkflowExecution execution, String eventType, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", execution.getId());
        payload.put("workflowId", execution.getWorkflowId());
        payload.put("workflowVersionId", execution.getWorkflowVersionId());
        payload.put("status", execution.getStatus().name());
        payload.put("currentStepIndex", execution.getCurrentStepIndex());
        payload.put("currentStepId", execution.getCurrentStepId());
        payload.put("initiatedBy", execution.getCreatedBy());
        payload.put("occurredAt", clock.instant());
        payload.putAll(details);
        outbox.append(ExecutionEventType.AGGREGATE_TYPE, execution.getId(), eventType, payload);
    }

    private static Map<String, Object> stepPayload(StepAttempt attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", attempt.step().id());
        payload.put("stepIndex", attempt.stepIndex());
        payload.put("stepType", attempt.step().type().name());
        payload.put("outcome", attempt.result().outcome());
        payload.put("attempts", attempt.attempts());
        if (attempt.result().message() != null) {
            payload.put("message", attempt.result().message());
        }
        return payload;
    }

    private static String firstStepId(ExecutionSnapshot snapshot) {
        return snapshot.definition().steps().isEmpty() ? null : snapshot.definition().step(snapshot.currentStepIndex()).id();
    }
}
