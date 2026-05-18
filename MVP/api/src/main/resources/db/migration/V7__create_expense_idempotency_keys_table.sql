-- Idempotency keys protect against duplicate expense creation from client retries.
-- If a network failure causes the client to retry the same POST /expenses request,
-- the server detects the duplicate key and returns the original expense silently.
--
-- The composite PK (user_id, idempotency_key) scopes keys per user — the same
-- client-generated UUID from two different users does not conflict.
CREATE TABLE expense_idempotency_keys (
    user_id         UUID            NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(255)    NOT NULL,
    expense_id      UUID            NOT NULL,
    expense_date    DATE            NOT NULL,
    expires_at      TIMESTAMPTZ     NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (expense_id, expense_date) REFERENCES expenses(id, expense_date)
);

-- Cleanup job index — nightly deletion of rows where expires_at <= NOW().
CREATE INDEX idx_idempotency_keys_expires ON expense_idempotency_keys (expires_at);

CREATE TRIGGER trg_idempotency_keys_set_updated_at
    BEFORE UPDATE ON expense_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_idempotency_keys_lock_created_at
    BEFORE UPDATE ON expense_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
