# Project Brief — patient-management-system

## What this is
A from-scratch backend engineering portfolio project: a healthcare-domain patient management system built as Java Spring Boot microservices — Docker, Postgres, Kafka, REST, gRPC, JWT, API Gateway, integration tests, AWS deployment via IaC.

The point of the project is to demonstrate the full microservices stack end to end, not just a CRUD service: sync REST between client and gateway, gRPC service-to-service, Kafka for async events, containerized, secured with JWT, deployed on AWS via Terraform.

## Current state
_(Update this section at the end of each phase — see PIPELINE.md. Last updated: Phase 0 complete.)_

- **Built:** `patient-service` — full CRUD on `/api/v1/patients`, layered architecture, Postgres in Docker Compose, Spring Boot 4.1.1, 21 passing tests (Testcontainers-backed).
- **Fixed in Phase 0:** schema/entity table-name mismatch, update NPE, missing `id` on response DTO, wrong status codes (404/409), hardcoded credentials (relocated to gitignored `.env`), inconsistent resource paths (standardized on plural `/patients`), unhandled malformed-date input (now 400).
- **Test setup:** all 21 tests run against Testcontainers-provided Postgres, including the original context-load test. `mvn test` passes on a clean checkout with no compose stack running — it needs only Docker.
- **Known issue, cosmetic:** error response bodies use three different key conventions (`Error`, `Message`, field-name map for validation) — inherited, not standardized yet.
- **Known issue, not yet fixed:** the original dev Postgres password, and a separate H2 credential from the very first commit, are exposed in git history. This repo is public, so both are treated as compromised. Relocating the value to `.env` was not rotation; rotation is a separate, still-pending step. Literal values are intentionally not named in any tracked file.
- **Not yet built:** auth-service, api-gateway, billing-service, analytics-service, notification-service, Kafka, gRPC, JWT/Spring Security, cross-service integration tests, AWS/Terraform.

## Tech stack
| Layer | Choice |
|---|---|
| Language / build | Java 17, Maven |
| Framework | Spring Boot 4.1.1 |
| Persistence | Spring Data JPA / Hibernate, `ddl-auto=validate` |
| Database | PostgreSQL, one DB per service |
| API Gateway | Spring Cloud Gateway (release train pinned to match the Spring Boot 4.x version in use — verify current pairing before adding) |
| Auth | Self-issued JWT via a dedicated Auth Service; gateway validates, downstream services trust the gateway |
| Service-to-service (sync) | gRPC |
| Async events | Kafka |
| Testing | JUnit + Testcontainers (real Postgres/Kafka in tests, not mocks) |
| IaC | Terraform |
| Deployment target | AWS ECS (Fargate), RDS, MSK, ALB |

## Repo layout decision (pending)
No root aggregator `pom.xml` yet — currently a folder-of-services monorepo. Decide when service #2 (`auth-service`) is added: likely a root reactor `pom.xml` for build convenience, with each service remaining independently deployable and no compile-time inter-service dependencies except a minimal shared module for gRPC-generated stubs and Kafka event DTOs, if one turns out to be needed.

## Non-goals for v1
- No service discovery / Eureka — service URLs are static config for now (small, fixed service count).
- No Kubernetes — ECS is the deployment target.
- No multi-region, no horizontal auto-scaling tuning.
- No schema registry for Kafka — plain JSON event payloads plus a shared DTO module; Avro/schema registry is a documented stretch goal, not a v1 requirement.

## Related docs
- `ARCHITECTURE.md` — what's implemented vs. planned, service diagrams
- `PIPELINE.md` — build phases and local dev setup
- `.claude/rules/` — security, coding standards, git rules