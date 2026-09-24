# FlowForge — Enterprise Workflow & Automation Platform

[![Java 21](https://img.shields.io/badge/Java-21-blue)](#) [![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen)](#) [![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791)](#) [![Kafka](https://img.shields.io/badge/Kafka-3.8-black)](#) [![Redis](https://img.shields.io/badge/Redis-7-red)](#)

FlowForge is a production-grade **modular monolith** for defining, versioning and executing business workflows
(approvals, notifications, webhooks) across many tenants. It is built with Java 21 and Spring Boot 3.5 following
Domain-Driven Design and hexagonal architecture, and it demonstrates the engineering patterns an enterprise
backend needs in one runnable system:

- dynamic **state-machine execution** driven by declarative JSON definitions,
- **multi-tenancy** enforced at the HTTP, service and persistence layers,
- a distributed **idempotency guard** for every state-changing request,
- the **transactional outbox** pattern with reliable Kafka publishing and dead-letter handling,
- stateless **JWT security** with refresh-token rotation and granular RBAC,
- end-to-end **observability** with OpenTelemetry traces, Prometheus metrics and Grafana.

---

## Table of contents

1. [Architecture](#1-architecture)
2. [Domain model](#2-domain-model)
3. [Core mechanics](#3-core-mechanics)
4. [Quick start (Docker)](#4-quick-start-docker)
5. [Operational guide](#5-operational-guide)
6. [API walk-through](#6-api-walk-through)
7. [Ports, URLs and credentials](#7-ports-urls-and-credentials)
8. [Observability guide](#8-observability-guide)
9. [Configuration reference](#9-configuration-reference)
10. [Development and testing](#10-development-and-testing)
11. [Production hardening checklist](#11-production-hardening-checklist)

---

## 1. Architecture

### Module map

```
com.flowforge
├── core                         cross-cutting concerns, no dependency on modules
│   ├── common                   BaseEntity / AuditEntity, JsonUtils, RFC 9457 GlobalExceptionHandler
│   ├── tenant                   TenantContext, X-Tenant-ID resolution, Hibernate filter aspect, JPA listener guard
│   ├── security                 JWT provider & filter, refresh-token rotation, SecurityFilterChain, RBAC evaluator
│   └── idempotency              @Idempotent aspect backed by Redis locks and response cache
├── modules                      bounded contexts; depend on core and on each other only via services/events
│   ├── identity                 Tenant, User, Role, Permission, provisioning, bootstrap
│   ├── workflow                 Workflow, WorkflowVersion (JSONB definition), parser/validator, publishing
│   ├── execution                WorkflowExecution state machine, StepExecutor strategies, engine, metrics
│   ├── outbox                   OutboxEvent, OutboxService, scheduled OutboxPublisher
│   └── notification             Kafka consumer, retry/DLT handling, idempotent processing
└── infrastructure/config        Kafka, Redis, async executors + context propagation, JPA auditing, OpenAPI
```

### Request flow

```
HTTP ──► TenantFilter ──► JwtAuthenticationFilter ──► @PreAuthorize ──► @Idempotent ──► Controller
                                                                                     │
                       ┌─────────────────────────────────────────────────────────────┘
                       ▼
             Application service (@Transactional)
                       │  save aggregate + OutboxEvent in the SAME transaction
                       ▼
             PostgreSQL ◄──── OutboxPublisher (@Scheduled) ────► Kafka topic ────► Notification consumer
                                                                       │                (retry → .DLT)
                                                                       └──► trace headers, tenant headers
```

### Design rules

| Rule | How it is enforced |
|---|---|
| `modules → core`, never the reverse | `core/security` reaches identity data through the `AccountDirectory` port implemented in `modules/identity`. |
| Aggregates own their invariants | `WorkflowExecution` validates every status transition; `WorkflowVersion` refuses edits once published. |
| Persistence is the last line of defence | Hibernate `@Filter` + `TenantEntityListener` reject cross-tenant rows even for loads by primary key. |
| Side effects leave through the outbox | No module publishes to Kafka directly; `OutboxService.append` requires an active transaction. |
| Executors are pure strategies | `StepExecutor` implementations never touch the database and are resolved from `Map<StepType, StepExecutor>`. |

## 2. Domain model

```
tenants ─┬─< users >─┬─< user_roles >─┬─ roles >─< role_permissions >─ permissions (global catalogue)
         │           └─< refresh_tokens
         ├─< workflows ─< workflow_versions (definition JSONB, DRAFT → PUBLISHED → DEPRECATED)
         └─< workflow_executions (status, current_step_index, context_data JSONB, idempotency_key)
                 └─< workflow_execution_steps (append-only attempt history)
outbox_events (aggregate_type, aggregate_id, event_type, payload JSONB, status PENDING/PUBLISHED/FAILED, retry_count)
```

Execution life cycle:

```
PENDING ──► IN_PROGRESS ──► COMPLETED
               │  ▲
               ▼  │  (approval decision)
             WAITING
               │
               ▼
             FAILED   (from IN_PROGRESS or WAITING)
```

### Workflow definition schema

```json
{
  "name": "purchase-approval",
  "steps": [
    { "id": "review",  "type": "APPROVAL",     "config": { "approverRole": "ADMIN", "prompt": "Approve ${input.amount}?" },
      "transitions": { "APPROVED": "notify", "REJECTED": "END" } },
    { "id": "notify",  "type": "EMAIL",        "config": { "to": "${input.requester}", "subject": "Approved" } },
    { "id": "erp",     "type": "HTTP_WEBHOOK", "config": { "url": "https://erp.example.com/hooks", "method": "POST" },
      "retry": { "maxAttempts": 3, "backoffMillis": 500 } }
  ]
}
```

- `transitions` map a step outcome to the next step id or `END`. An unmapped `SUCCESS` falls through to the next
  step in order; any other unmapped outcome fails the execution.
- `${path}` placeholders resolve against the execution context: `input.*`, `variables.*`,
  `steps.<id>.output.*`.
- The parser rejects duplicate ids, unknown transition targets, unreachable steps and missing required config.

## 3. Core mechanics

| Concern | Implementation |
|---|---|
| **State machine** | `WorkflowExecutionEngine` runs on the `workflowExecutor` pool, resolves the `StepExecutor` for each step, applies the retry policy, resolves the `Transition` (sealed `Advance`/`Complete`) and persists every change through `ExecutionStateManager`. APPROVAL steps park the run in `WAITING` until a decision arrives. |
| **Transactional outbox** | `ExecutionStateManager` writes the aggregate and the `OutboxEvent` in one transaction. `OutboxPublisher` polls PENDING rows, sends with `KafkaTemplate` (`acks=all`, synchronous acknowledgement) and marks rows PUBLISHED under optimistic locking, so several instances can run the relay concurrently. Failed sends back off exponentially and end in `FAILED` after `max-retries`. |
| **Consumer resilience** | `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries` retries transient failures and forwards poison records to `<topic>.DLT`, preserving the partition. `ProcessedEventStore` (Redis `SETNX`) makes processing idempotent under at-least-once delivery. |
| **Idempotency guard** | `@Idempotent` reads `X-Idempotency-Key`, scopes it per tenant and user, acquires `SET ... NX PX` in Redis, fingerprints the payload and caches the `ResponseEntity`. Replays return the cached response with `X-Idempotent-Replay: true`; a different payload with the same key returns 422; a concurrent duplicate returns 409. |
| **Multi-tenancy** | `X-Tenant-ID` (or the JWT tenant claim) binds `TenantContext`. `TenantFilterAspect` enables the Hibernate `tenantFilter` on every repository call, `TenantEntityListener` stamps and verifies `tenant_id`, and `TenantPermissionEvaluator` checks aggregate ownership in `hasPermission(...)`. |
| **Security** | Stateless HS256 access tokens (15 min) carry tenant and authorities. Opaque refresh tokens are hashed at rest, rotated on every use and grouped in families: replaying a rotated token revokes the whole session. |
| **Observability** | Micrometer Tracing → OpenTelemetry → OTLP; trace context crosses thread pools (`ContextPropagatingTaskDecorator`) and Kafka headers. Custom meters: `workflow_execution_duration_seconds`, `workflow_execution_failures_total`, `workflow_step_retries_total`, `outbox_events_total`, `notification_dead_letters_total`. |

## 4. Quick start (Docker)

Prerequisites: Docker Desktop (or any Docker Engine with Compose v2) with at least 6 GB of memory assigned;
the full stack runs ten containers, and Kafka in particular becomes unresponsive when the VM is starved.

```bash
git clone https://github.com/DilmurodElmurodov/flowforge.git
cd flowforge
docker compose up --build -d      # builds the backend image and starts all 10 services
docker compose ps                 # wait until backend, postgres, redis, kafka report "healthy"
```

Then open http://localhost:18080 — it redirects to the Swagger UI. The first start:

1. waits for PostgreSQL, Redis and Kafka health checks,
2. applies the Flyway migrations (`V1__init_schema.sql`, `V2__seed_permissions.sql`),
3. provisions the bootstrap tenant `default` (`11111111-1111-1111-1111-111111111111`) with an `ADMIN` user
   `admin` / `Admin123!` holding every permission.

Stop with `docker compose down` (add `-v` to also drop the data volumes).

## 5. Operational guide

### Start / stop / rebuild

| Task | Command |
|---|---|
| Start everything | `docker compose up -d` |
| Rebuild the backend after a code change | `docker compose up --build -d backend` |
| Infrastructure only (run the app from the IDE) | `docker compose up -d postgres redis kafka jaeger` |
| Stop, keep data | `docker compose down` |
| Stop and wipe data | `docker compose down -v` |

### Database migrations

Flyway runs automatically at start-up against `classpath:db/migration`; Hibernate is set to `ddl-auto: validate`
so the entity mapping is verified against the migrated schema. To add a migration, create
`src/main/resources/db/migration/V<n>__<description>.sql` and rebuild. Inspect the history in pgAdmin
(`flyway_schema_history`) or with:

```bash
docker compose exec postgres psql -U flowforge -d flowforge -c 'select version, description, success from flyway_schema_history'
```

### Logs

```bash
docker compose logs -f backend                      # follow application logs
docker compose logs --since 10m backend | grep ERROR
docker compose logs -f kafka postgres redis         # infrastructure
```

Every log line carries `[app,traceId,spanId] [tenant=<uuid> user=<uuid>]`, so a trace id from Jaeger can be
grepped straight from the logs.

### pgAdmin (PostgreSQL browser)

1. Open http://localhost:5050 and sign in with `admin@admin.com` / `admin`.
2. The server **FlowForge (postgres)** is pre-registered; expand it and enter the database password `flowforge`
   when prompted.
3. Browse `flowforge → Schemas → public → Tables` (workflows, workflow_executions, outbox_events, ...).

Useful queries:

```sql
select id, status, current_step_id, idempotency_key, started_at, completed_at from workflow_executions order by created_at desc;
select event_type, status, retry_count, published_at from outbox_events order by created_at desc limit 20;
```

### Redis Commander (Redis browser)

Open http://localhost:8082 (or 8081, see [ports](#7-ports-urls-and-credentials)). Keys of interest:

- `idempotency:<tenant>:<user>:<key>` cached responses, `...:lock` in-flight locks,
- `notification:processed:<eventId>` consumer deduplication markers.

### Swagger UI

http://localhost:18080/swagger-ui.html. Click **Authorize**, paste the access token from
`POST /api/v1/auth/login` (send `X-Tenant-ID` on that call), and every endpoint is callable from the browser.

## 6. API walk-through

```bash
BASE=http://localhost:18080
T=11111111-1111-1111-1111-111111111111

# 1. authenticate (X-Tenant-ID is required for auth endpoints)
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H "X-Tenant-ID: $T" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin123!"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

# 2. create a workflow (state-changing POSTs need X-Idempotency-Key)
WF=$(curl -s -X POST $BASE/api/v1/workflows -H "$AUTH" -H "X-Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"name":"purchase-approval","description":"Manager approval, then notify"}' | jq -r .id)

# 3. add a version and publish it
curl -s -X POST $BASE/api/v1/workflows/$WF/versions -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "definition": {
    "name": "purchase-approval",
    "steps": [
      {"id":"review","type":"APPROVAL","config":{"approverRole":"ADMIN","prompt":"Approve ${input.amount}?"},
       "transitions":{"APPROVED":"notify","REJECTED":"END"}},
      {"id":"notify","type":"EMAIL","config":{"to":"${input.requester}","subject":"Approved","body":"Done"}},
      {"id":"erp","type":"HTTP_WEBHOOK","config":{"url":"http://localhost:8080/actuator/health","method":"GET"},
       "retry":{"maxAttempts":3,"backoffMillis":500}}
    ]
  }}' | jq '{versionNumber,status}'
curl -s -X POST $BASE/api/v1/workflows/$WF/versions/1/publish -H "$AUTH" | jq .status

# 4. start an execution (202 Accepted) — it parks at the approval step
EX=$(curl -s -X POST $BASE/api/v1/executions -H "$AUTH" -H "X-Idempotency-Key: order-42" \
  -H 'Content-Type: application/json' \
  -d "{\"workflowId\":\"$WF\",\"input\":{\"amount\":1200,\"requester\":\"alice@acme.io\"}}" | jq -r .id)
curl -s $BASE/api/v1/executions/$EX -H "$AUTH" | jq .status          # WAITING

# 5. approve and watch it complete
curl -s -X POST $BASE/api/v1/executions/$EX/approval -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVED","comment":"ok"}' | jq .status
sleep 2; curl -s $BASE/api/v1/executions/$EX -H "$AUTH" | jq .status  # COMPLETED
curl -s $BASE/api/v1/executions/$EX/steps -H "$AUTH" | jq '.[] | {stepId,status,outcome,attempts}'

# 6. replay the same idempotency key: same execution, no re-execution
curl -si -X POST $BASE/api/v1/executions -H "$AUTH" -H "X-Idempotency-Key: order-42" -H 'Content-Type: application/json' \
  -d "{\"workflowId\":\"$WF\",\"input\":{\"amount\":1200,\"requester\":\"alice@acme.io\"}}" | grep -i "x-idempotent-replay"
```

### Endpoint summary

| Method & path | Authority | Notes |
|---|---|---|
| `POST /api/v1/auth/login` / `refresh` / `logout` | public | require `X-Tenant-ID` |
| `POST /api/v1/tenants` | `TENANT_MANAGE` | provisions tenant + ADMIN user, idempotent |
| `POST /api/v1/users`, `GET /api/v1/users/me` | `USER_MANAGE` / authenticated | |
| `POST /api/v1/roles`, `GET /api/v1/roles` | `ROLE_MANAGE` | roles compose global permission codes |
| `POST /api/v1/workflows` | `WORKFLOW_CREATE` | idempotent |
| `POST /api/v1/workflows/{id}/versions` | `WORKFLOW_CREATE` | validates the definition, stores a DRAFT |
| `POST /api/v1/workflows/{id}/versions/{n}/publish` | `WORKFLOW_PUBLISH` | deprecates the previous published version |
| `POST /api/v1/executions` | `WORKFLOW_EXECUTE` | idempotent, returns 202 |
| `GET /api/v1/executions/{id}`, `/steps` | `EXECUTION_READ` | object-level tenant check |
| `POST /api/v1/executions/{id}/approval` | `EXECUTION_APPROVE` | approver must hold `ROLE_<approverRole>` |

Errors follow RFC 9457 (`application/problem+json`) with a stable `code` property, e.g.
`IDEMPOTENT_REQUEST_IN_PROGRESS`, `TENANT_MISMATCH`, `ILLEGAL_EXECUTION_TRANSITION`.

## 7. Ports, URLs and credentials

Host ports are chosen not to collide with other local stacks and can be overridden through environment variables
or the `.env` file.

| Service | URL / port | Credentials | Override variable |
|---|---|---|---|
| Backend (Swagger redirect at `/`) | http://localhost:18080 | `admin` / `Admin123!` (tenant `1111…1111`) | `FLOWFORGE_BACKEND_PORT` |
| Actuator | http://localhost:18080/actuator, `/actuator/health`, `/actuator/prometheus` | public | – |
| PostgreSQL | `localhost:15432`, db `flowforge` | `flowforge` / `flowforge` | `FLOWFORGE_POSTGRES_PORT` |
| pgAdmin | http://localhost:5050 | `admin@admin.com` / `admin` | `FLOWFORGE_PGADMIN_PORT` |
| Redis | `localhost:16379` | – | `FLOWFORGE_REDIS_PORT` |
| Redis Commander | http://localhost:8082 (default 8081 when free) | – | `FLOWFORGE_REDIS_COMMANDER_PORT` |
| Kafka (host listener) | `localhost:9092` | – | `FLOWFORGE_KAFKA_PORT` |
| Prometheus | http://localhost:9090 | – | `FLOWFORGE_PROMETHEUS_PORT` |
| Grafana | http://localhost:3001 | `admin` / `admin` | `FLOWFORGE_GRAFANA_PORT` |
| Jaeger UI | http://localhost:16686 | – | – |

## 8. Observability guide

- **Traces (Jaeger)** — open http://localhost:16686, pick service `flowforge`, and search. A single trace spans
  the HTTP request, the JDBC calls, the async engine steps, the outbox Kafka send and the notification consumer.
- **Metrics (Prometheus)** — http://localhost:9090 scrapes `backend:8080/actuator/prometheus` every 15 s.
  Useful queries:
  - `histogram_quantile(0.95, sum(rate(workflow_execution_duration_seconds_bucket[5m])) by (le, workflow))`
  - `sum(rate(workflow_execution_failures_total[5m])) by (workflow, step_type)`
  - `sum(rate(outbox_events_total[5m])) by (outcome)`
  - `notification_dead_letters_total`
  - `http_server_requests_seconds_count{uri="/api/v1/executions"}`
- **Dashboards (Grafana)** — http://localhost:3001 (`admin` / `admin`). Prometheus is provisioned as the default
  datasource; build panels from the queries above or import a JVM/Spring dashboard (e.g. Grafana id 4701).
- **Health** — `/actuator/health` shows component details (db, redis, kafka, disk); `/actuator/health/readiness`
  is what Docker's health check probes.

## 9. Configuration reference

All values live in `src/main/resources/application.yml` as `${ENV_VAR:default}` placeholders.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/flowforge`, `flowforge` | PostgreSQL |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Redis (set `SPRING_DATA_REDIS_PASSWORD` if AUTH is required) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | OpenTelemetry collector / Jaeger |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | lower in production |
| `JWT_SECRET` | dev placeholder | **must** be overridden (≥ 32 bytes) |
| `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL` | `15m`, `7d` | token lifetimes |
| `ACTUATOR_EXPOSURE`, `HEALTH_SHOW_DETAILS` | `*`, `always` | tighten to `health,info,prometheus` / `when_authorized` |
| `BOOTSTRAP_ENABLED`, `BOOTSTRAP_TENANT_ID`, `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD` | off | first-run tenant provisioning |
| `OUTBOX_POLL_INTERVAL_MS`, `KAFKA_LISTENER_CONCURRENCY` | `1000`, `3` | throughput tuning |
| `EXECUTOR_CORE_POOL_SIZE`, `EXECUTOR_MAX_POOL_SIZE` | `4`, `16` | workflow worker pool |
| `LOG_LEVEL_APP` | `INFO` | `com.flowforge` log level |

## 10. Development and testing

Requirements: JDK 21 (the Gradle toolchain resolves it automatically) and Docker for the integration suite.

```bash
./gradlew test               # unit tests: engine, idempotency aspect, outbox publisher/processor, parser, JWT
./gradlew integrationTest    # Testcontainers (PostgreSQL, Redis, Kafka): end-to-end execution, outbox → Kafka →
                             # notification, idempotency guarantees incl. concurrency, tenant isolation, RBAC,
                             # refresh-token rotation
./gradlew check              # both (stop the Compose stack first on small Docker VMs: `docker compose stop`)
./gradlew bootRun            # run from source against `docker compose up -d postgres redis kafka jaeger`
```

Running from source against the Compose infrastructure:

```bash
export DB_URL=jdbc:postgresql://localhost:15432/flowforge REDIS_PORT=16379
export BOOTSTRAP_ENABLED=true BOOTSTRAP_TENANT_ID=11111111-1111-1111-1111-111111111111 BOOTSTRAP_ADMIN_PASSWORD='Admin123!'
./gradlew bootRun
```

Project layout worth knowing:

- `src/main/resources/db/migration` — Flyway schema and seed data.
- `Dockerfile` — multi-stage build (JDK build stage with a BuildKit Gradle cache, layered jar on a non-root JRE).
- `docker-compose.yml`, `.env`, `ops/` — full local stack, Prometheus scrape config, Grafana datasource, pgAdmin servers.

## 11. Production hardening checklist

- Set `JWT_SECRET`, database and broker credentials from a secret store; never ship the defaults.
- Restrict `ACTUATOR_EXPOSURE` to `health,info,prometheus`, set `HEALTH_SHOW_DETAILS=when_authorized`, and remove
  `/actuator/**` and the API docs from the public matchers in `SecurityConfig` (or serve them on a management port).
- Replace `LoggingEmailGateway` / `LoggingNotificationSender` with real providers.
- Run more than one instance: the outbox publisher and Kafka consumers are safe to scale horizontally.
- Point `OTLP_TRACES_ENDPOINT` at a collector and lower `TRACING_SAMPLING_PROBABILITY`.
- Use `replication-factor` ≥ 3 for Kafka topics and a managed PostgreSQL with backups.

---

Licensed for internal evaluation. Contributions: open a pull request against `main` with green `./gradlew check`.
