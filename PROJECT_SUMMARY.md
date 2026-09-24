# FlowForge — Project Summary

**What it is.** A multi-tenant workflow and automation platform: tenants author versioned JSON workflow
definitions (approval, e-mail and webhook steps), start executions through an idempotent REST API, and receive
reliable event notifications. Built as a modular monolith in Java 21 / Spring Boot 3.5.6 with PostgreSQL, Redis
and Kafka.

**Why it matters.** The code base is a reference implementation of the patterns enterprise backends need:

| Pattern | Where |
|---|---|
| Hexagonal modular monolith with DDD aggregates | `core/*` vs `modules/*`, ports such as `AccountDirectory`, `EmailGateway`, `NotificationSender` |
| Dynamic state machine + Strategy | `WorkflowExecutionEngine`, `StepExecutor`, `Map<StepType, StepExecutor>`, sealed `Transition` |
| Transactional outbox → Kafka, at-least-once with idempotent consumers | `OutboxService`, `OutboxPublisher`, `ProcessedEventStore` |
| Retry with exponential backoff + dead-letter topic | `KafkaConfig` (`DefaultErrorHandler`), `DeadLetterListener` |
| Distributed idempotency guard | `@Idempotent`, `IdempotencyAspect`, `RedisIdempotencyStore` |
| Multi-tenancy: header/JWT resolution, Hibernate filter, entity listener, object-level RBAC | `core/tenant`, `TenantPermissionEvaluator` |
| Stateless JWT + rotating refresh tokens with reuse detection | `JwtTokenProvider`, `RefreshTokenService` |
| Observability: OpenTelemetry tracing across threads and Kafka, custom Micrometer meters | `AsyncConfig`, `ObservabilityConfig`, `ExecutionMetrics` |

**Quality gates.** 35 unit tests (JUnit 5 + Mockito) and 12 Testcontainers integration tests (PostgreSQL, Redis,
Kafka) cover the engine, idempotency guard, outbox relay, tenant isolation, RBAC and token rotation end to end.

**Run it.** `docker compose up --build -d`, then http://localhost:18080 (Swagger), http://localhost:5050
(pgAdmin), http://localhost:8082 (Redis Commander), http://localhost:9090 (Prometheus), http://localhost:3001
(Grafana), http://localhost:16686 (Jaeger). Default login: `admin` / `Admin123!` with
`X-Tenant-ID: 11111111-1111-1111-1111-111111111111`.

See `README.md` for the full architecture, operational guide and configuration reference.
