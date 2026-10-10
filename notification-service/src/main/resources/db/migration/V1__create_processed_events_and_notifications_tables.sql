-- Event IDs this consumer has already applied. Inserted in the same transaction as the
-- business effect, so a redelivered event (Kafka is at-least-once) is recognised and skipped.
CREATE TABLE processed_events (
    event_id     UUID        NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);

-- One recorded notification per patient.created event.
--
-- patient_id is a plain reference to a patient owned by patient-service: no foreign
-- key, because each service owns its own database (database-per-service).
CREATE TABLE notifications (
    id         UUID        NOT NULL,
    event_id   UUID        NOT NULL,
    patient_id UUID        NOT NULL,
    kind       VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notifications_pkey PRIMARY KEY (id),
    -- Second line of defence behind processed_events: one notification per event.
    CONSTRAINT notifications_event_id_key UNIQUE (event_id),
    CONSTRAINT notifications_kind_check CHECK (kind IN ('WELCOME')),
    CONSTRAINT notifications_status_check CHECK (status IN ('RECORDED'))
);
