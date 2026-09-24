package com.flowforge.modules.execution.domain;

import com.flowforge.core.common.JsonDocument;
import com.flowforge.core.common.exception.ConflictException;
import com.flowforge.core.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregate root of a single run of a workflow version. State transitions are validated here so that no
 * caller (engine, API, recovery job) can put the aggregate into an inconsistent state.
 */
@Entity
@Table(name = "workflow_executions")
@Filter(name = TenantScopedEntity.TENANT_FILTER, condition = TenantScopedEntity.TENANT_FILTER_CONDITION)
public class WorkflowExecution extends TenantScopedEntity {

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false, updatable = false)
    private UUID workflowVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Column(name = "current_step_index", nullable = false)
    private int currentStepIndex = 0;

    @Column(name = "current_step_id", length = 100)
    private String currentStepId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_data", nullable = false, columnDefinition = "jsonb")
    private String contextData;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WorkflowExecution() {
    }

    public WorkflowExecution(UUID workflowId, UUID workflowVersionId, String idempotencyKey,
                             Map<String, Object> contextData, String firstStepId) {
        this.workflowId = workflowId;
        this.workflowVersionId = workflowVersionId;
        this.idempotencyKey = idempotencyKey;
        this.contextData = JsonDocument.write(contextData);
        this.currentStepId = firstStepId;
    }

    // ---------------------------------------------------------------- state transitions

    public void start(Instant now) {
        transition(ExecutionStatus.IN_PROGRESS);
        this.startedAt = now;
    }

    public void resume() {
        transition(ExecutionStatus.IN_PROGRESS);
    }

    public void advanceTo(int stepIndex, String stepId, Map<String, Object> context) {
        requireStatus(ExecutionStatus.IN_PROGRESS);
        this.currentStepIndex = stepIndex;
        this.currentStepId = stepId;
        this.contextData = JsonDocument.write(context);
    }

    public void park(Map<String, Object> context) {
        transition(ExecutionStatus.WAITING);
        this.contextData = JsonDocument.write(context);
    }

    public void complete(Instant now, Map<String, Object> context) {
        transition(ExecutionStatus.COMPLETED);
        this.contextData = JsonDocument.write(context);
        this.completedAt = now;
    }

    public void fail(Instant now, String reason, Map<String, Object> context) {
        transition(ExecutionStatus.FAILED);
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 2000));
        if (context != null) {
            this.contextData = JsonDocument.write(context);
        }
        this.completedAt = now;
    }

    public void updateContext(Map<String, Object> context) {
        this.contextData = JsonDocument.write(context);
    }

    private void transition(ExecutionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("ILLEGAL_EXECUTION_TRANSITION",
                    "Execution " + getId() + " cannot move from " + status + " to " + target);
        }
        this.status = target;
    }

    private void requireStatus(ExecutionStatus expected) {
        if (status != expected) {
            throw new ConflictException("ILLEGAL_EXECUTION_STATE",
                    "Execution " + getId() + " is " + status + ", expected " + expected);
        }
    }

    // ---------------------------------------------------------------- accessors

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getWorkflowVersionId() {
        return workflowVersionId;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public String getCurrentStepId() {
        return currentStepId;
    }

    public Map<String, Object> getContextData() {
        return JsonDocument.read(contextData);
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
