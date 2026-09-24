package com.flowforge.modules.identity.domain;

import com.flowforge.core.common.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/** A customer organisation. Tenants are the root of every isolation boundary in the system. */
@Entity
@Table(name = "tenants")
public class Tenant extends AuditEntity {

    @Column(name = "name", nullable = false, length = 150, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TenantStatus status = TenantStatus.ACTIVE;

    protected Tenant() {
    }

    public Tenant(String name) {
        this.name = name;
    }

    public Tenant(UUID id, String name) {
        setId(id);
        this.name = name;
    }

    public void suspend() {
        this.status = TenantStatus.SUSPENDED;
    }

    public void activate() {
        this.status = TenantStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public TenantStatus getStatus() {
        return status;
    }
}
