package com.flowforge.modules.identity.domain;

import com.flowforge.core.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Granular capability (e.g. {@code WORKFLOW_EXECUTE}). Global catalogue, seeded by Flyway. */
@Entity
@Table(name = "permissions")
public class Permission extends BaseEntity {

    @Column(name = "code", nullable = false, length = 100, unique = true)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    protected Permission() {
    }

    public Permission(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
