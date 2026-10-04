# Project Brief

## Project
**Patient Management System — Microservices Backend**
Build a portfolio-grade Java backend that demonstrates deliberate microservice boundaries and distributed-systems behavior. The value is not the number of technologies used; every REST, gRPC, Kafka, database, resilience, and cloud decision should have a defensible reason.
Build phase-by-phase and get each phase working quickly, but do not skip the failure behavior or consistency guarantees that the phase exists to demonstrate.

## Technology Stack

Use these technologies unless a project decision explicitly changes them:

- Java (user-approved installed LTS JDK baseline)
- Spring Boot
- Spring Framework
- Maven Wrapper, multi-module
- Spring Cloud Gateway
- Spring Security + Nimbus JOSE/JWT
- PostgreSQL + Flyway
- Apache Kafka
- Redpanda for local Kafka-compatible development
- gRPC + Protocol Buffers
- Resilience4j
- Testcontainers
- Spring Boot Actuator
- Micrometer + OpenTelemetry
- Prometheus + Grafana
- Docker + Docker Compose
- Terraform + LocalStack where practical
- AWS ECS Fargate, RDS PostgreSQL, MSK, ALB
- GitHub Actions

### Version Selection Rule

Do not treat this file as a version catalog. When a technology is first introduced:

1. Check its current official documentation and release/support policy.
2. Choose the latest stable GA release, or latest stable LTS where that project has an LTS channel.
3. Verify compatibility with the rest of the stack before adding it.
4. Prefer Spring Boot's dependency management for libraries it manages.
5. Verify AWS-supported engine/platform versions before infrastructure work.
6. Do not use preview, milestone, RC, beta, alpha, nightly, or snapshot releases unless explicitly approved.

Java is intentionally handled differently: use the user-approved installed LTS JDK baseline and do not change its major version without approval.

## Repository Shape
Use a Maven multi-module repository (root aggregator POM):
- `auth-service`
- `patient-service`
- `billing-service`
- `notification-service`
- `analytics-service`
- `api-gateway`
- a small shared conventions module where genuinely useful
Do not put domain logic into the shared module. Each service owns its domain and persistence.

## Core Architecture
### External HTTP
Client -> API Gateway -> services.
The gateway is the only public application entry point.
Use consistent RFC 7807 (`application/problem+json`) errors across HTTP services.
### Authentication
Auth Service owns users, credentials, refresh tokens, signing keys, and token issuance.
Gateway verifies access JWTs using Auth Service's public JWKS key and forwards trusted identity headers downstream.
Downstream services must not be publicly reachable; the trust model depends on this network boundary.
### Databases
Use database-per-service.
No cross-service foreign keys, joins, or direct database reads.
Cross-service IDs are references only.
All schema changes use Flyway migrations checked into source control.
### Asynchronous Communication
Patient Service publishes patient lifecycle events to Kafka only after the database transaction commits.
Notification and Analytics consume independently using separate consumer groups.
Consumers are idempotent because Kafka delivery is at-least-once. Deduplicate using a stable event ID and a processed-events record.
Do not describe this as broker-level exactly-once delivery.
### Synchronous Communication
Patient -> Billing is the candidate gRPC interaction.
Before Phase 3, confirm there is a real synchronous business requirement. A stronger candidate than “return a billing ID” is a real-time eligibility/pre-authorization result that Patient cannot know locally.
If no genuine synchronous dependency exists, make Billing event-driven and remove gRPC rather than forcing it into the project.
When gRPC is used, wrap the call with explicit timeout/circuit-breaker behavior and test the failure path.

## Auth Service V1
Roles:
- `ADMIN`
- `DOCTOR`
- `NURSE`
- `BILLING_STAFF`
- `PATIENT`
One role per user in V1. JWT may expose it as `roles: ["DOCTOR"]` for forward compatibility.
### Patient Self-Scope
`PATIENT` requires resource ownership checks, not RBAC alone.
Patient access tokens carry a `patientId` claim. Services serving patient-scoped data must verify the requested resource belongs to that `patientId`.
### Tokens
Access token:
- RS256 JWT
- 15-minute lifetime
- claims: `sub`, `roles`, optional `patientId`, `iat`, `exp`, `iss`
Refresh token:
- opaque random token, not a JWT
- 7-day lifetime
- store only its hash
- rotate on every refresh
- support revocation
Auth Service keeps the private signing key. Gateway verifies through JWKS.
### Auth Data
`users` owns user identity, email, password hash, role, optional `patient_id`, active state, and creation timestamp.
`refresh_tokens` owns the hashed token, user reference, expiry, revocation state, and creation timestamp.
`patient_id` is a plain UUID reference to Patient Service, never a database FK.
### Gateway Identity Headers
After JWT verification, forward:
- `X-User-Id`
- `X-User-Roles`
- `X-Patient-Id` when present
Never trust these headers from public clients. Gateway must overwrite/remove inbound spoofed identity headers.

## Patient Service
V1 should provide patient CRUD with Bean Validation, correct HTTP semantics, pagination, conflict handling, Flyway-managed PostgreSQL schema, and role/patient self-scope authorization.
Patient creation is the source of `patient.created`.

## Kafka Event Contract
Every event needs at minimum a stable `eventId`, event type/version, occurrence timestamp, aggregate/patient ID, required payload, and correlation/trace context.
Keep event contracts explicit and versionable. Notification and Analytics must not depend on Patient Service's database.

## Billing and Resilience
If Billing remains synchronous, decide failure semantics before implementation.
If patient creation continues during Billing failure, encode a real state such as `PENDING_BILLING` and define reconciliation/retry. Do not document “reconcile later” without a mechanism.
Resilience4j policy must explicitly define timeout, circuit breaker, bounded retry only when safe, and fallback behavior.

## Observability
Use Spring Boot Actuator, Micrometer, and OpenTelemetry for structured logs, correlation IDs, distributed traces, Kafka trace propagation, health/readiness endpoints, Prometheus metrics, and later Grafana dashboards.
Do not let observability polish block earlier core phases.

## Testing
Use JUnit/Spring testing support with Testcontainers.
Important proof points:
- real PostgreSQL integration tests
- Kafka/Redpanda integration behavior
- invalid input and authorization failures
- duplicate Kafka event -> one business effect
- rolled-back patient transaction -> no published event
- Billing unavailable -> defined circuit-breaker/fallback behavior
- patient cannot access another patient's record
- spoofed gateway identity headers are ineffective
Agent-generated code is a draft until tricky transaction, idempotency, auth, and resilience paths are reviewed.

## Build Order
### Phase 0 — Foundations
Maven multi-module structure, shared conventions, CI, Docker Compose, PostgreSQL/Redpanda development stack.
### Phase 1 — Load-Bearing Core
Auth Service, Patient Service, API Gateway, JWT/JWKS flow, authorization, migrations, integration tests. Do not move on until this path is solid.
### Phase 2 — Event-Driven Flow
After-commit Patient events, Notification consumer, Analytics consumer, independent groups, idempotency, duplicate-delivery tests.
### Phase 3 — gRPC / Billing
Confirm synchronous justification first. Then define `.proto`, implement Billing and Patient client, add Resilience4j, and test outages.
### Phase 4 — AWS / Terraform
Terraform for ALB -> ECS Fargate -> RDS/MSK. Use AWS-supported engine versions, `LATEST` Fargate platform where appropriate, and an approved secrets solution.
Validate locally where practical, then perform one real AWS deployment for evidence and tear it down after the demo.
### Phase 5 — System Failure Tests
Exercise end-to-end flows, duplicate Kafka delivery, transaction rollback, Billing outage, and basic concurrent/load behavior.
### Phase 6 — Portfolio Polish
README architecture rationale, trade-offs, diagrams/demo evidence, and concise explanation of sync vs async decisions.

## Known Trade-offs
V1 deliberately does not include:
- full transactional outbox / Debezium relay
- distributed saga
- large analytics/OLAP platform
- permanent live AWS environment
- multi-role users
- patient self-service account claiming
- login rate limiting or account lockout
- CORS and rate limiting at the API gateway
- soft delete / archival of patient records: V1 hard-deletes on `DELETE`; a real system would soft-delete or archive for audit purposes
- network-enforced gateway trust locally: services bind to loopback only, but anything on the developer machine can call them directly and bypass the gateway (including forging `X-User-*` headers); real enforcement is AWS network isolation
- PII risk from non-database exceptions in the `common` module's catch-all logger: it logs the full exception message and stack trace server-side. Accepted for V1 — not a live leak (database error detail is already suppressed via `logServerErrorDetail=false`), and exception messages that could carry PII are prevented going forward by the security rules.
- service discovery (Eureka or similar): service URLs are static configuration for a small, fixed set of services
- Kubernetes: ECS Fargate is the deployment target
- multi-region deployment or autoscaling tuning
- a Kafka schema registry / Avro: events are JSON with explicit, versioned contracts
After-commit publishing reduces one dual-write failure mode but does not guarantee eventual publication after a post-commit broker failure. Document this honestly.

## If Time Runs Short
Cut in this order:
1. Analytics Service polish or Analytics entirely
2. advanced dashboards/observability polish
3. extended load testing
4. nonessential AWS/demo polish
Do not cut Auth/Patient/Gateway correctness, Kafka idempotency proof, transaction behavior, or the chosen synchronous failure-path test.

## Per-Service Definition of Done
Before moving past a service:
- follows shared error/logging/correlation conventions
- schema changes are Flyway migrations
- critical service/domain logic has unit tests
- important persistence/messaging behavior has Testcontainers integration tests
- relevant negative paths are tested
- health/readiness endpoints work
- no secrets are in source/config
- tricky transaction, idempotency, auth, or resilience code has been reviewed
- deliberate simplifications are documented
The goal is not “five Spring services.” The goal is a backend where communication patterns, ownership boundaries, and failure behavior are intentional and demonstrable.
