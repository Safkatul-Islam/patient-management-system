CREATE TABLE patients
(
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    address         VARCHAR(255) NOT NULL,
    date_of_birth   DATE         NOT NULL,
    registered_date DATE         NOT NULL,
    -- Named explicitly: the API maps violations of this constraint to 409. It matches the name
    -- Postgres generated for the inline UNIQUE in the old data.sql, so baselined databases agree.
    CONSTRAINT patients_email_key UNIQUE (email)
);
