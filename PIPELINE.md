# Pipeline

Build order and local dev instructions. Update each phase's status as it completes.

## Phases

### Phase 0 — Stabilize patient-service (complete)
1. ✅ Diagnosed and fixed the entity/table name mismatch; verified clean-DB boot.
2. ✅ Docker Compose for local Postgres (`docker-compose.yml` at repo root).
3. ✅ Upgraded Spring Boot 4.0.3 → 4.1.1. Credentials relocated to gitignored `.env` (not yet rotated — see Known limitations).
4. ✅ Fixed, each with a regression test: update NPE, missing `id`, status codes, resource paths (standardized on `/api/v1/patients`), malformed-date handling. `delete-patient.http` scheme bug fixed and committed.
5. ✅ Switched `PatientServiceApplicationTests` to the Testcontainers config. All 21 tests pass with the compose stack down — `mvn test` needs only Docker.

### Phase 1 — Auth
- `auth-service`: issues JWTs.
- API Gateway enforces JWT on Patient endpoints.
- Decide monorepo structure (root aggregator pom or not) before or alongside this phase.

### Phase 2 — gRPC
- `billing-service` + `billing_service.proto`.
- Patient Service as gRPC client, triggered on a real event (e.g. patient creation).
- Decide the transactional boundary between Patient creation and the Billing call (see ARCHITECTURE.md open decisions).

### Phase 3 — Kafka
- Patient Service produces to the `patients` topic.
- `analytics-service` and `notification-service` as consumers.

### Phase 4 — Tests, IaC, deploy
- Testcontainers integration tests across services.
- Terraform for ECS/RDS/MSK/ALB.
- Deploy to AWS; get a working public URL.

## Local dev

```bash
docker compose up -d          # starts Postgres (and later Kafka)
cd patient-service
./mvnw spring-boot:run        # runs on port 4000
./mvnw test                   # run tests
```

## Known limitations
_(keep current — don't let this go stale)_
- No CI pipeline yet. When one arrives, it needs Docker available for Testcontainers-backed tests to run.
- The original dev Postgres password, and a separate H2 credential from the very first commit, are exposed in git history. This repo is public, so both are treated as compromised. Relocating the value to `.env` was not rotation — rotating it is a separate, still-pending step. Do not name literal credential values in tracked files, docs included.
- Error response bodies use inconsistent key conventions across handlers (`Error`, `Message`, field-name map). Worth a standardization pass, not urgent.
- No service discovery — service URLs are static config.
- No schema registry for Kafka events (plain JSON) — documented stretch goal.

## Related docs
- `PROJECT_BRIEF.md` — stack, current state
- `ARCHITECTURE.md` — implemented vs. planned architecture