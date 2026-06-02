-- v1.1 #2 — sliding-window failed-login lockout.
-- Lockout fires when COUNT(*) of failures in the last 10 minutes >= 5.

CREATE TABLE user_login_failures (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    attempted_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    ip_address      INET            NULL
);

-- Index supports both the sliding-window count and the nightly cleanup scan.
CREATE INDEX idx_user_login_failures_user_at
    ON user_login_failures (user_id, attempted_at DESC);
