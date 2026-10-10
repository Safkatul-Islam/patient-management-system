-- Idempotency ledger: one row per event this service has applied. The row is inserted in the
-- same transaction as the business effect, so a redelivered event finds it and is skipped.
CREATE TABLE processed_events (
    event_id     UUID        NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);

-- Patient registrations per UTC calendar day of the event's occurredAt.
CREATE TABLE daily_registrations (
    day           DATE   NOT NULL,
    registrations BIGINT NOT NULL,
    CONSTRAINT daily_registrations_pkey PRIMARY KEY (day),
    CONSTRAINT daily_registrations_registrations_check CHECK (registrations >= 0)
);
