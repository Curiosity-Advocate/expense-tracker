CREATE TABLE job_execution_state (
    job_name      TEXT        PRIMARY KEY,
    status        TEXT        NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'ALERTED')),
    attempt_count INT         NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
