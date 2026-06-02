-- Stores the jti (JWT ID) of tokens that have been explicitly revoked via logout.
-- The auth filter checks this table on every request — if the jti appears here
-- the token is rejected even if it hasn't expired yet.
-- Nightly cleanup removes rows where expires_at has passed since they'd be
-- rejected by expiry anyway.
CREATE TABLE revoked_tokens (
    token_jti   UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id),
    revoked_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Cleanup job deletes rows where expires_at <= NOW().
CREATE INDEX idx_revoked_tokens_expires ON revoked_tokens (expires_at);

CREATE TRIGGER trg_revoked_tokens_set_updated_at
    BEFORE UPDATE ON revoked_tokens
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_revoked_tokens_lock_created_at
    BEFORE UPDATE ON revoked_tokens
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
