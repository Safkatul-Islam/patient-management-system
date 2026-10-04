# Architecture

> **Living baseline.** Planned components are not implementation claims. Update this file when a
> service boundary, communication pattern, trust boundary, or deployment topology changes.

## Status

- **[Implemented]** — exists and is verified
- **[Planned]** — agreed direction, not yet implemented
- **[Decision pending]** — intentionally unresolved

## Logical Architecture

```text
                         ┌───────────────────┐
                         │ Frontend / Client │
                         └─────────┬─────────┘
                                   │ REST
                                   ▼
                         ┌───────────────────┐
                         │    API Gateway    │
                         │  JWT verification │
                         └──────┬─────┬──────┘
                                │     │
                             REST     REST
                                │     │
                                ▼     ▼
                      ┌────────────┐ ┌───────────────┐
                      │    Auth    │ │    Patient    │
                      │  Service   │ │    Service    │
                      └─────┬──────┘ └──────┬────────┘
                            │               ├── gRPC ──► Billing Service
                            ▼               │
                         Auth DB            └── event ─► Kafka
                                                        │
                                              ┌─────────┴─────────┐
                                              ▼                   ▼
                                         Notification         Analytics
                                           Service             Service
```

**Billing gRPC is [Decision pending].** Keep it only if Patient genuinely needs an immediate
Billing answer; otherwise use an event-driven interaction.

## Service Responsibilities

| Component | Responsibility | Status |
|---|---|---|
| API Gateway | Public entry point, routing, JWT verification, trusted identity propagation | [Implemented] |
| Auth Service | Users, credentials, access/refresh tokens, JWKS | [Implemented] |
| Patient Service | Patient records with role + self-scope authorization; lifecycle events | [Implemented] records/authorization; [Planned] lifecycle events |
| Billing Service | Billing/eligibility concern; sync contract not final | [Decision pending] |
| Notification Service | Independent event consumer for notifications | [Planned] |
| Analytics Service | Minimal event-driven aggregation | [Planned] |
| Kafka | Async fan-out between independent consumers | [Planned] |

Shared code may contain cross-cutting conventions, never another service's domain logic.

## Communication Rules

| From | To | Pattern | Reason |
|---|---|---|---|
| Client | Gateway | REST | External request/response boundary |
| Gateway | Auth / Patient | REST | Internal request routing |
| Patient | Billing | gRPC | Only for a genuine immediate-response need |
| Patient | Kafka | Event publish | Fan-out and eventual consistency |
| Kafka | Notification / Analytics | Separate consumer groups | Independent async processing |

## Data Ownership

```text
Auth Service      Patient Service      Notification      Analytics
     │                  │                   │                │
     ▼                  ▼                   ▼                ▼
 Auth DB           Patient DB          owned state       owned state
```

- Database-per-service: no cross-service joins, FKs, or direct DB reads.
- Cross-service IDs are plain references only.
- All schema changes use Flyway.
- A single local PostgreSQL instance may host multiple logical service databases.
- Billing persistence is defined only after its contract is finalized.

## Authentication and Trust Boundary

```text
Client + JWT
     │
     ▼
API Gateway ── validate JWKS / strip spoofed identity headers
     │          inject verified X-User-* headers
     ▼
Protected Service ── role + resource ownership authorization
```

Gateway-provided identity may include `X-User-Id`, `X-User-Roles`, and `X-Patient-Id`.
Downstream services may trust them only while direct public access is blocked. A `PATIENT` must
match the requested resource's `patientId`; role membership alone is insufficient.

**As implemented (Phase 1):**

- **Identity header contract:** `X-User-Id` (UUID, the token `sub`), `X-User-Roles` (exactly one
  role name in V1, no `ROLE_` prefix), `X-Patient-Id` (UUID, present iff the role is `PATIENT`),
  plus `X-Correlation-Id`. Both the gateway (token claims) and patient-service (headers) reject
  anything else, including multiple roles.
- **Token verification at the gateway:** RS256 is pinned in code (never read from the token
  header); `typ=JWT`, `iss`, `aud=pms-api` and a present, unexpired `exp` are required. Keys come
  only from the configured JWKS URI through a bounded cache: an unknown `kid` triggers at most one
  refetch per 30 s cooldown, a warm cache keeps validating through an auth-service outage, and a
  cold-cache JWKS failure returns 503 (for up to the cooldown) rather than 401 or 500.
- **Header hygiene:** every inbound `X-User-*` / `X-Patient-Id` header (any case, `_` or `-`) is
  stripped on every request before security runs; verified values are injected per route.
  `Authorization` is forwarded to auth-service (which re-verifies its own tokens) and removed
  toward patient-service. Client `Forwarded` / `X-Forwarded-*` headers are not trusted or passed on.
- **Fail closed:** paths without a security rule return 401/403, not 404; malformed paths
  (encoded `..`/`/`, `//`, `;` parameters) are rejected with 400 by Spring Security's firewall.
- **Not yet:** CORS, rate limiting, and retries (retry/circuit-breaker policy is Phase 3).
- **Local development:** services bind to `127.0.0.1` by default, so they are unreachable from the
  LAN; anything on the developer machine can still call them directly and forge identity headers.
  Real enforcement of the trust boundary is AWS network isolation (Phase 4).

## Local Development Deployment

**Target topology [Planned].** Today only PostgreSQL runs in Docker Compose. auth-service,
patient-service and api-gateway run as local JVM processes bound to `127.0.0.1` (see `README.md`
for the run sequence); Billing, Kafka/Redpanda, Notification and Analytics are not built yet.

```text
┌────────────────────────── Docker Compose Network ──────────────────────────┐
│                                                                           │
│ Client → API Gateway ──► Auth Service ──► Auth DB                         │
│             │                                                             │
│             └──────────► Patient Service ──► Patient DB                    │
│                              │                                             │
│                              ├── gRPC ──► Billing Service                  │
│                              └── event ─► Redpanda / Kafka                 │
│                                              ├──► Notification Service     │
│                                              └──► Analytics Service        │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

One PostgreSQL server may host the separate logical databases locally; ownership rules remain.

## AWS Deployment Direction

```text
Internet
   │
   ▼
Application Load Balancer
   │
   ▼
┌──────────────────────────── ECS Fargate ────────────────────────────┐
│ API Gateway ──► Auth Service                                       │
│      │                                                             │
│      └────────► Patient Service ── gRPC ──► Billing Service         │
│                       │                                             │
│                       └── Kafka producer ───────────────┐            │
│                                                        │            │
│ Notification Service ◄── Kafka consumer ───────────────┤            │
│ Analytics Service    ◄── Kafka consumer ───────────────┘            │
└──────────────────┬────────────────────────────┬─────────────────────┘
                   │                            │
                   ▼                            ▼
             RDS Postgres                  Amazon MSK
           service-owned DBs              patient events
```

Deployment intent:

- ALB/Gateway path is the public application entry point.
- Service tasks, RDS, and MSK stay on private network paths.
- Logical database ownership remains service-specific even if physical layout changes.
- Choose an AWS secrets mechanism before deployment.

## Repository and Build Structure

- Maven multi-module repository. The root `pom.xml` is an **aggregator only** (`packaging=pom`,
  `<modules>`): one reactor build, one CI command (`mvn -B verify` at the root).
- The root POM is deliberately **not a `<parent>`**. Each module inherits
  `spring-boot-starter-parent` directly, so every service stays independently buildable and
  deployable, and no service's build config is coupled to another's. Accepted cost: shared build
  settings (Java version, Spotless, compiler config) are repeated per module.
- `common` holds cross-cutting HTTP conventions only (RFC 7807 handler base, correlation-ID
  filter), never domain logic.

## Architectural Invariants

- Gateway is the external application entry point.
- Services never access another service's database.
- HTTP errors use RFC 7807 consistently.
- Patient events publish only after the owning DB transaction commits.
- Kafka consumers use independent consumer groups and stable event-ID deduplication.
- Kafka is treated as at-least-once; do not claim broker-level exactly-once semantics.
- Synchronous remote calls have explicit timeout and failure behavior.
- Correlation/trace context crosses REST, gRPC, and Kafka boundaries.
- Development and demos use synthetic patient data only.

## Open Architectural Decisions

1. **Billing sync boundary** — confirm the synchronous business requirement before defining `.proto`.
2. **Billing failure semantics** — fail Patient creation or persist a state such as `PENDING_BILLING`
   with a real reconciliation path.
3. **AWS secrets** — Secrets Manager vs. Parameter Store.
4. **Production Flyway execution** — run-on-boot vs. dedicated migration step.
5. **Idempotency retention** — retention policy for processed-event records.
6. **Refresh-token retention** — retention/cleanup policy for expired and revoked refresh-token
   records.

Use an ADR later when a major decision needs durable rationale.

## Related Documents

- `PROJECT_BRIEF.md` — goals, stack, phases, and trade-offs
- `PIPELINE.md` — runtime request, event, and failure flows
