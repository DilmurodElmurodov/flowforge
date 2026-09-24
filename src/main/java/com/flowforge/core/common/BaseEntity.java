package com.flowforge.core.common;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

/**
 * Root of every JPA aggregate.
 *
 * <p>Identifiers are client-assigned UUIDs so aggregates are addressable before they hit the database
 * (important for the outbox pattern, where the event must reference the aggregate id inside the same
 * transaction). Because the id is never {@code null}, Spring Data cannot infer "newness" from it, so we
 * implement {@link Persistable} explicitly and avoid the extra {@code SELECT} that {@code merge} would issue.
 *
 * <p>Every aggregate carries a {@link Version} column so concurrent modifications are detected optimistically.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        return Objects.equals(id, ((BaseEntity) other).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Hibernate.getClass(this), id);
    }
}
