package com.flowforge.modules.workflow.domain;

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
 * Immutable snapshot of a workflow's step graph. The {@code definition} column is JSONB, so the raw document is
 * preserved exactly as authored (and remains queryable in PostgreSQL).
 */
@Entity
@Table(name = "workflow_versions")
@Filter(name = TenantScopedEntity.TENANT_FILTER, condition = TenantScopedEntity.TENANT_FILTER_CONDITION)
public class WorkflowVersion extends TenantScopedEntity {

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private String definition;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WorkflowVersionStatus status = WorkflowVersionStatus.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected WorkflowVersion() {
    }

    public WorkflowVersion(UUID workflowId, int versionNumber, Map<String, Object> definition) {
        this.workflowId = workflowId;
        this.versionNumber = versionNumber;
        this.definition = JsonDocument.write(definition);
    }

    public void publish(Instant now) {
        if (status != WorkflowVersionStatus.DRAFT) {
            throw new ConflictException("VERSION_NOT_DRAFT",
                    "Version " + versionNumber + " is " + status + " and cannot be published");
        }
        this.status = WorkflowVersionStatus.PUBLISHED;
        this.publishedAt = now;
    }

    public void deprecate() {
        if (status == WorkflowVersionStatus.PUBLISHED) {
            this.status = WorkflowVersionStatus.DEPRECATED;
        }
    }

    public void updateDefinition(Map<String, Object> newDefinition) {
        if (status != WorkflowVersionStatus.DRAFT) {
            throw new ConflictException("VERSION_IMMUTABLE", "Only DRAFT versions can be modified");
        }
        this.definition = JsonDocument.write(newDefinition);
    }

    public boolean isPublished() {
        return status == WorkflowVersionStatus.PUBLISHED;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public Map<String, Object> getDefinition() {
        return JsonDocument.read(definition);
    }

    public WorkflowVersionStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
