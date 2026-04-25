-- Short-lived step-up authentication tokens.
-- Required when accessing cross-user data via the asUserId parameter.
-- This is the "sudo" pattern — user re-authenticates to confirm intent,
-- not just session validity.
--
-- token_hash: the sudo token value is hashed before storage (same principle
-- as passwords). A compromised DB does not yield valid sudo tokens.
--
-- used_at: single-use enforcement. Once consumed, subsequent requests with
-- the same token are rejected even if still within the expiry window.
-- This prevents replay attacks within the 15-minute window.
CREATE TABLE sudo_tokens (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(255)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ     NOT NULL,
    used_at     TIMESTAMPTZ     NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sudo_tokens_user ON sudo_tokens(user_id, expires_at);