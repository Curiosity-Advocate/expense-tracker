-- Prevents duplicate expense creation from client retries.
-- The client generates a UUID before the request and sends it with every attempt.
-- The server uses INSERT ... ON CONFLICT (idempotency_key) DO NOTHING.
-- Keys expire after 24 hours — a retry after that creates a new expense.
CREATE TABLE expense_idempotency_keys (
    idempotency_key UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users(id),
    expense_id      UUID        NOT NULL,
    expense_date    DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL
                                DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_idempotency_user
    ON expense_idempotency_keys(user_id, expires_at);