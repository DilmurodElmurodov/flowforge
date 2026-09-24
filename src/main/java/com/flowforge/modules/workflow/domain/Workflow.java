package com.flowforge.modules.workflow.domain;

import com.flowforge.core.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

/** Aggregate root of a workflow; versions are separate aggregates referencing it. */
@Entity
@Table(name = "workflows")
@Filter(name = TenantScopedEntity.TENANT_FILTER, condition = TenantScopedEntity.TENANT_FILTER_CONDITION)
public class Workflow extends TenantScopedEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WorkflowStatus status = WorkflowStatus.ACTIVE;

    @Column(name = "latest_version", nullable = false)
    private int latestVersion = 0;

    protected Workflow() {
    }

    public Workflow(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** Allocates the next version number. Guarded by the aggregate's optimistic lock. */
    public int nextVersionNumber() {
        latestVersion += 1;
        return latestVersion;
    }

    public void archive() {
        this.status = WorkflowStatus.ARCHIVED;
    }

    public boolean isActive() {
        return status == WorkflowStatus.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public int getLatestVersion() {
        return latestVersion;
    }
}
