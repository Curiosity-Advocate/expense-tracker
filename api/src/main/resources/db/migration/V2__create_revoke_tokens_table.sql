-- Tracks invalidated JWT tokens for synchronous logout.
-- token_jti is the JWT "jti" claim — a unique ID baked into every token at issuance.
-- On every authenticated request the gateway filter looks up jti here.
-- If found, the token is rejected even if not yet expired.
-- expires_at enables a nightly cleanup job — rows past expiry are dead weight.
--
-- revoked_at has no DEFAULT — the application must set it explicitly.
-- This preserves the audit relationship between revoked_at and expires_at:
--   revoked_at < expires_at  → early logout (normal case)
--   revoked_at > expires_at  → delayed revocation, token already expired (async case)
CREATE TABLE revoked_tokens (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NOT NULL REFERENCES users(id),
    token_jti   VARCHAR(255)    NOT NULL UNIQUE,
    revoked_at  TIMESTAMPTZ     NOT NULL,
    expires_at  TIMESTAMPTZ     NOT NULL,
 
    -- Revocation cannot be set to a future time — that has no meaning.
    -- Small buffer of 5 seconds accounts for clock skew between app servers.
    CONSTRAINT chk_revoked_at_not_future
        CHECK (revoked_at <= NOW() + INTERVAL '5 seconds')
);
 
CREATE INDEX idx_revoked_tokens_jti     ON revoked_tokens(token_jti);
CREATE INDEX idx_revoked_tokens_expires ON revoked_tokens(expires_at);
 