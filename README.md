# patient-management-system

A patient management backend built as Java Spring Boot microservices: an API gateway that verifies
JWTs, an auth service that issues them, and a patient service that enforces role and patient
self-scope authorization, each with its own PostgreSQL database. Kafka events, billing over gRPC
and AWS deployment via Terraform are the planned next phases. This is a portfolio project in
progress, not a production system, and it uses synthetic data only.

**Status:** Phase 1 (API gateway, auth-service, patient-service) is complete. Phase 2
(after-commit `patient.created` events, Notification and Analytics consumers) is next.

| Document | What it covers |
|---|---|
| [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) | Goals, stack, architecture rules, build order, trade-offs |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Service boundaries, trust boundary, what is implemented vs planned |
| [`PIPELINE.md`](PIPELINE.md) | Runtime request, event and failure flows |

## Running locally

Prerequisites: JDK 21, Maven 3.9+, Docker. Run every command from the repository root.

1. **Configure secrets.** Copy the template and fill in every value; the comments in
   [`.env.example`](.env.example) explain each one, including how to generate the RS256 signing
   key. `.env` is gitignored and must never be committed.
   ```bash
   cp .env.example .env
   ```
2. **Start PostgreSQL.** One container hosts `patient_db` and `auth_db`.
   ```bash
   docker compose up -d
   ```
   On a fresh volume, `auth_db` and its role are created automatically. On a volume created before
   auth-service existed, create them once by hand (on Git Bash, prefix with `MSYS_NO_PATHCONV=1`):
   ```bash
   docker exec pms-postgres bash /docker-entrypoint-initdb.d/01-create-auth-db.sh
   ```
3. **Build** (the tests start throwaway PostgreSQL containers through Testcontainers):
   ```bash
   mvn -B verify                    # or: mvn -B package -DskipTests
   ```
4. **One-time Flyway baseline**, only for a `patient_db` created by the old `data.sql` script
   (it has the `patients` table but no Flyway history). Fresh databases skip this.
   ```bash
   java -jar patient-service/target/patient-service-0.0.1-SNAPSHOT.jar \
     --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=1
   ```
   Stop it once it has started; later runs need no flags.
5. **Start the services**, each in its own terminal, in any order (the gateway fetches the
   signing keys on first use):
   ```bash
   java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar
   java -jar patient-service/target/patient-service-0.0.1-SNAPSHOT.jar
   java -jar api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar
   ```

| Service | Port | Called by |
|---|---|---|
| api-gateway | 4004 | clients: the only entry point |
| auth-service | 4005 | the gateway (and the gateway's JWKS fetch) |
| patient-service | 4000 | the gateway |

All three bind to `127.0.0.1` only. Send requests to the gateway at `http://localhost:4004`;
example requests are in [`api-requests/`](api-requests/), starting with
`api-requests/gateway/login-and-list-patients.http`. On first start auth-service creates an `ADMIN`
account from `AUTH_BOOTSTRAP_ADMIN_EMAIL` / `AUTH_BOOTSTRAP_ADMIN_PASSWORD` in `.env`.

Calling a service directly on its own port bypasses the gateway, including its JWT verification.
That is an accepted local-development trade-off; see the Known Trade-offs in `PROJECT_BRIEF.md`.
