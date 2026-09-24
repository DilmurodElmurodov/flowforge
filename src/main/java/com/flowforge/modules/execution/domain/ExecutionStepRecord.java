package com.flowforge.modules.execution.domain;

import com.flowforge.core.common.JsonDocument;
import com.flowforge.core.tenant.TenantScopedEntity;
import com.flowforge.modules.workflow.model.StepType;
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

/** Append-only audit trail of every step attempt of an execution. */
@Entity
@Table(name = "workflow_execution_steps")
@Filter(name = TenantScopedEntity.TENANT_FILTER, condition = TenantScopedEntity.TENANT_FILTER_CONDITION)
public class ExecutionStepRecord extends TenantScopedEntity {

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "step_id", nullable = false, length = 100)
    private String stepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 30)
    private StepType stepType;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "outcome", length = 100)
    private String outcome;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private String output;

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    protected ExecutionStepRecord() {
    }

    public ExecutionStepRecord(UUID executionId, int stepIndex, String stepId, StepType stepType, String status,
                               String outcome, int attempts, Map<String, Object> output, String message,
                               Instant startedAt, Instant finishedAt) {
        this.executionId = executionId;
        this.stepIndex = stepIndex;
        this.stepId = stepId;
        this.stepType = stepType;
        this.status = status;
        this.outcome = outcome;
        this.attempts = attempts;
        this.output = output == null ? null : JsonDocument.write(output);
        this.message = message == null ? null : message.substring(0, Math.min(message.length(), 2000));
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public String getStepId() {
        return stepId;
    }

    public StepType getStepType() {
        return stepType;
    }

    public String getStatus() {
        return status;
    }

    public String getOutcome() {
        return outcome;
    }

    public int getAttempts() {
        return attempts;
    }

    public Map<String, Object> getOutput() {
        return output == null ? null : JsonDocument.read(output);
    }

    public String getMessage() {
        return message;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
