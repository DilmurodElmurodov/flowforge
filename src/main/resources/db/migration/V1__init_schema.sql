-- =====================================================================================================
-- FlowForge - initial schema
-- Conventions: UUID primary keys (client-assigned), TIMESTAMPTZ in UTC, optimistic-lock "version" column,
-- tenant_id on every tenant-owned table (enforced by Hibernate filter + entity listener at runtime).
-- =====================================================================================================

-- ---------------------------------------------------------------- identity ----------------------------
CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenants_name UNIQUE (name),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE permissions (
    id          UUID PRIMARY KEY,
    code        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

CREATE TABLE roles (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenants (id),
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_roles_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL REFERENCES tenants (id),
    username      VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_tenant_username UNIQUE (tenant_id, username),
    CONSTRAINT uq_users_tenant_email    UNIQUE (tenant_id, email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_family  ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------- workflow ----------------------------
CREATE TABLE workflows (
    id             UUID PRIMARY KEY,
    tenant_id      UUID          NOT NULL REFERENCES tenants (id),
    name           VARCHAR(150)  NOT NULL,
    description    VARCHAR(1000),
    status         VARCHAR(30)   NOT NULL,
    latest_version INTEGER       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_workflows_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_workflows_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE workflow_versions (
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenants (id),
    workflow_id    UUID        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    version_number INTEGER     NOT NULL,
    definition     JSONB       NOT NULL,
    status         VARCHAR(30) NOT NULL,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_workflow_versions_number UNIQUE (workflow_id, version_number),
    CONSTRAINT ck_workflow_versions_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED'))
);
CREATE INDEX idx_workflow_versions_workflow_status ON workflow_versions (workflow_id, status);
CREATE INDEX idx_workflow_versions_definition_gin  ON workflow_versions USING GIN (definition);

-- ---------------------------------------------------------------- execution ---------------------------
CREATE TABLE workflow_executions (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants (id),
    workflow_id         UUID         NOT NULL REFERENCES workflows (id),
    workflow_version_id UUID         NOT NULL REFERENCES workflow_versions (id),
    status              VARCHAR(30)  NOT NULL,
    current_step_index  INTEGER      NOT NULL DEFAULT 0,
    current_step_id     VARCHAR(100),
    context_data        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key     VARCHAR(128) NOT NULL,
    failure_reason      VARCHAR(2000),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    version             BIGINT       NOT NULL DEFAULT 0,
    -- Scoped per tenant: two tenants may legitimately generate the same client key.
    CONSTRAINT uq_workflow_executions_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_workflow_executions_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'WAITING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_workflow_executions_tenant_created ON workflow_executions (tenant_id, created_at DESC);
CREATE INDEX idx_workflow_executions_status_created ON workflow_executions (status, created_at);
CREATE INDEX idx_workflow_executions_workflow       ON workflow_executions (workflow_id);

CREATE TABLE workflow_execution_steps (
    id           UUID PRIMARY KEY,
    tenant_id    UUID         NOT NULL REFERENCES tenants (id),
    execution_id UUID         NOT NULL REFERENCES workflow_executions (id) ON DELETE CASCADE,
    step_index   INTEGER      NOT NULL,
    step_id      VARCHAR(100) NOT NULL,
    step_type    VARCHAR(30)  NOT NULL,
    status       VARCHAR(30)  NOT NULL,
    outcome      VARCHAR(100),
    attempts     INTEGER      NOT NULL DEFAULT 1,
    output       JSONB,
    message      VARCHAR(2000),
    started_at   TIMESTAMPTZ  NOT NULL,
    finished_at  TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_workflow_execution_steps_execution ON workflow_execution_steps (execution_id, started_at);

-- ---------------------------------------------------------------- outbox ------------------------------
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    tenant_id       UUID,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    last_error      VARCHAR(2000),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);
-- Partial index: the publisher only ever scans PENDING rows, so keep that index tiny and hot.
CREATE INDEX idx_outbox_events_pending   ON outbox_events (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_aggregate ON outbox_events (aggregate_id, created_at);
