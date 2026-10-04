# System Pipeline

> **Living runtime-flow document.** This file shows important request and event flows.
> Build order belongs in `PROJECT_BRIEF.md`. Planned flows are not implementation claims.

## Status

- **[Implemented]** — implemented and verified end to end
- **[Planned]** — agreed intended flow
- **[Decision pending]** — intentionally unresolved

## 1. Login and Token Issuance [Implemented]

1. Client sends credentials through the Gateway.
2. Auth Service verifies the user.
3. Auth issues a short-lived access JWT plus an opaque refresh token.
4. Only the refresh-token hash is stored.

```text
Client
  │ POST /auth/login
  ▼
API Gateway
  │
  ▼
Auth Service ──► Auth DB
  │               verify user / store refresh-token hash
  │
  └────────────► access JWT + refresh token ──► Client
```

Refresh tokens are rotated on use. Access tokens remain stateless until expiry.

## 2. Protected Request [Implemented]

1. Client sends an access JWT.
2. Gateway validates it using Auth JWKS.
3. Gateway strips spoofed identity headers and injects verified ones.
4. The downstream service performs role and resource-level authorization.

```text
Client + JWT
     │
     ▼
API Gateway
     ├── validate signature / expiry / issuer
     ├── remove client X-User-* headers
     └── inject verified identity
     │
     ▼
Patient Service
     ├── role check
     └── patient ownership check when required
     │
     ▼
API Response / RFC 7807 error
```

For role `PATIENT`, the requested resource must match the verified `patientId`.

## 3. Patient Creation [Planned]

1. Patient Service validates and authorizes the request.
2. It writes inside a DB transaction.
3. The transaction commits.
4. Only after commit, `patient.created` is published.
5. Downstream consumers do not block the HTTP response.

```text
Authorized request
      │
      ▼
Patient Service ──► Patient DB transaction
      │                    │
      │                  COMMIT
      │                    │
      ├──── return response┘
      │
      └──── publish patient.created ──► Kafka
```

A rolled-back transaction must never publish the event.

## 4. Kafka Fan-Out [Planned]

```text
                    patient.created
                          │
                          ▼
                        Kafka
                          │
             ┌────────────┴────────────┐
             ▼                         ▼
    Notification group          Analytics group
             │                         │
      dedupe by eventId          dedupe by eventId
             │                         │
             ▼                         ▼
    notification action          update aggregates
```

Both consumer groups operate independently; one failing consumer must not block the other.

Minimum event metadata:

- stable `eventId`
- event type/version
- patient ID
- occurrence timestamp
- required payload
- correlation/trace context

## 5. Billing Interaction [Decision pending]

Do not keep gRPC merely for technology coverage. First confirm whether Patient truly needs an
immediate Billing answer.

```text
Need Billing result before Patient can continue?
                 │
          ┌──────┴──────┐
          │             │
         NO            YES
          │             │
          ▼             ▼
   async event      Patient Service
                         │ gRPC
                         ▼
                   Billing Service
                         │
                  ┌──────┴──────┐
                  ▼             ▼
               success        failure
                  │             │
               continue     timeout / circuit breaker /
                            explicit fallback
```

If Patient creation survives a Billing outage, persist a real state such as `PENDING_BILLING` and
define retry/reconciliation instead of merely saying "reconcile later."

## 6. Refresh and Logout [Implemented]

```text
Refresh token
     │
     ▼
Auth Service
     ├── hash + lookup token
     ├── reject expired / revoked / already-used token
     ├── revoke old token
     ├── issue new token pair
     └── store new refresh-token hash
```

Logout revokes the relevant refresh-token state.

## 7. Important Failure Paths

| Failure | Expected behavior |
|---|---|
| Invalid/expired JWT | Gateway rejects before protected service execution |
| Forged `X-User-*` headers | Gateway removes or overwrites them |
| Patient accesses another patient's record | Ownership check rejects it |
| Patient transaction rolls back | No `patient.created` event |
| Kafka redelivers an event | Same `eventId` produces one business effect |
| Notification consumer fails | Analytics continues independently |
| Analytics consumer fails | Notification continues independently |
| Billing times out | Explicit circuit-breaker/fallback behavior |
| Kafka publish fails after DB commit | Failure is visible; V1 may lose that event |

The last case is a known consequence of after-commit publishing. A transactional outbox is future
work if stronger publication guarantees become necessary.

## Pipeline Invariants

- External application traffic enters through the Gateway.
- Gateway authentication does not replace downstream authorization.
- DB commit happens before `patient.created` publication.
- Kafka is treated as at-least-once delivery.
- Consumers are idempotent at the business level.
- Async consumers do not block the Patient HTTP response.
- Correlation/trace context survives Kafka through message headers.
- Synchronous calls use bounded timeouts.
- Degraded behavior is never silently presented as success.

## When to Update This File

Update this file when request/event order, sync-vs-async boundaries, transaction behavior,
timeouts/retries/fallbacks, or other important runtime behavior changes.

Do not document every CRUD endpoint; keep only flows that help contributors understand the system.

## Related Documents

- `ARCHITECTURE.md` — service boundaries, ownership, communication, and deployment
- `PROJECT_BRIEF.md` — goals, stack, phases, and known trade-offs
