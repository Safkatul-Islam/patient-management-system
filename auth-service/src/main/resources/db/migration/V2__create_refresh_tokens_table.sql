-- Opaque refresh tokens. Only the SHA-256 hex digest of each token is stored, never
-- the token itself. A token is single-use: rotation sets revoked_at on the old row
-- and inserts a new one in the same transaction.
--
-- revocation_reason records why a token stopped working, because only one reason
-- is a theft signal: presenting a ROTATED token again means someone replayed it.
-- A LOGOUT or REUSE_DETECTED token is simply rejected.
CREATE TABLE refresh_tokens (
    id                UUID        PRIMARY KEY,
    user_id           UUID        NOT NULL REFERENCES users (id),
    token_hash        VARCHAR(64) NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ NULL,
    revocation_reason VARCHAR(20) NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_revocation_reason_check
        CHECK (revocation_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED')),
    -- A token is revoked exactly when it has a reason.
    CONSTRAINT refresh_tokens_revocation_consistency_check
        CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
