-- Accounts that can authenticate against auth-service. One role per user.
--
-- patient_id is a plain reference to a patient owned by patient-service: no foreign
-- key, because each service owns its own database (database-per-service).
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    patient_id    UUID         NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_role_check
        CHECK (role IN ('ADMIN', 'DOCTOR', 'NURSE', 'BILLING_STAFF', 'PATIENT')),
    -- A PATIENT account must be linked to a patient; no other role may be.
    CONSTRAINT users_patient_link_check CHECK ((role = 'PATIENT') = (patient_id IS NOT NULL))
);

-- One login account per patient. Partial, so the NULL patient_id of every staff
-- account is not constrained.
CREATE UNIQUE INDEX users_patient_id_key ON users (patient_id) WHERE patient_id IS NOT NULL;
