# Architecture

This file distinguishes **implemented** from **planned**. Do not read the planned sections as describing current behavior.

## Implemented

### patient-service
Layered architecture: `controller → service → repository`, with a hand-written `mapper` and separate `dto/` request/response types.

**Entity — Patient**
| Field | Type |
|---|---|
| id | UUID |
| name | String |
| email | String (unique) |
| address | String |
| dateOfBirth | LocalDate |
| registeredDate | LocalDate (required on create only; omitted on update leaves the original value unchanged) |

**Endpoints** (`/api/v1/patients` — standardized in Phase 0)
| Method | Path | Notes |
|---|---|---|
| GET | `/patients` | list all, each with `id` |
| POST | `/patients` | create; 409 on duplicate email |
| PUT | `/patients/{id}` | update; 404 if not found, 409 on email conflict with another patient |
| DELETE | `/patients/{id}` | 204; 404 if not found |

Response DTO includes `id` (string form of the UUID). Malformed dates on create/update return 400 with a clean message, no exception details in the body. Old singular/bare-id paths (`/patient`, bare `/{id}`) are gone — retired, not aliased.

Database: `patient_db`, running in Docker Compose locally (see PIPELINE.md). Going forward, each new service gets its own Postgres database — no shared database across service boundaries.

## Planned

### Dev architecture (local, Docker Compose)

```
Frontend (Client)
     |  REST
     v
 API Gateway ------------------+--------------+
     | REST                    | REST         |
     v                         v              |
Auth Service              Patient Service <----+
                             |         |
                        gRPC |         | Kafka Producer
                             v         v
                     Billing Service   Kafka Topic (patients)
                    (gRPC Server,           |        |
                  billing_service.proto)    v        v
                                  Analytics Service  Notification Service
                                  (Kafka Consumer)   (Kafka Consumer)
```

All services run in one Docker network locally.

### Deployment architecture (AWS)

```
Frontend Client
     |
     v
Application Load Balancer (public subnet)
     |
     v
+------------------------ ECS Cluster ------------------------+
|  API Gateway   Auth Service   Patient Service   Billing      |
|  (ECS Task)    (ECS Task)     (ECS Task,        Service      |
|                                gRPC client +     (ECS Task,   |
|                                Kafka producer)   gRPC server) |
|  Analytics Service                                            |
|  (ECS Task, Kafka consumer)                                   |
+----------------------------------------------------------------+
     |                                      |
     v                                      v
   RDS                                MSK (private subnet)
(Auth DB, Patient DB,               Kafka Topic (patient)
 one DB per service)
```

### Service responsibilities (planned)
- **API Gateway** — routes REST from the frontend, enforces JWT on protected routes.
- **Auth Service** — issues (and if needed refreshes) JWTs.
- **Patient Service** — owns patient records; on relevant writes, calls Billing over gRPC and publishes events to the `patients` Kafka topic.
- **Billing Service** — gRPC server, contract defined in `billing_service.proto`.
- **Analytics Service** — Kafka consumer, read-only downstream of patient events.
- **Notification Service** — Kafka consumer, triggers notifications off patient events.

### Open design decisions (fill in as they're made)
- Transactional boundary between Patient creation and the Billing gRPC call: rollback vs. eventual consistency + reconciliation. **Not yet decided** — decide in Phase 2, not before.
- Root aggregator `pom.xml` vs. folder-of-independent-services. **Not yet decided** — decide when `auth-service` is added.

## Related docs
- `PROJECT_BRIEF.md` — stack, current state, non-goals
- `PIPELINE.md` — build phases