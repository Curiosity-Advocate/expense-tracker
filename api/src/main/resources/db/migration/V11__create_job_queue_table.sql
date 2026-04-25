-- DB-based job queue using FOR UPDATE SKIP LOCKED.
-- Two workers polling simultaneously will never pick the same row —
-- one locks it, the other skips it and moves to the next.
-- This is the behaviour you'd pay for in RabbitMQ, free from PostgreSQL.
CREATE TABLE job_queue (
    id              UUID        PRIMARY KEY,
    job_type        VARCHAR(50) NOT NULL,
    user_id         UUID        NULL REFERENCES users(id),
    payload         JSONB       NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     INT         NOT NULL DEFAULT 0,
    max_retries     INT         NOT NULL DEFAULT 10,
    next_retry_at   TIMESTAMPTZ NULL,
    picked_up_at    TIMESTAMPTZ NULL,
    completed_at    TIMESTAMPTZ NULL,
    last_error      TEXT        NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_job_type
        CHECK (job_type IN (
            'BANK_SYNC',
            'NORMALISE_TRANSACTION',
            'AI_CATEGORISE',
            'ARCHIVE_PARTITION',
            'CREATE_PARTITION'
        )),

    CONSTRAINT chk_status
        CHECK (status IN (
            'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER'
        ))
);

CREATE TRIGGER trg_job_queue_set_updated_at
    BEFORE UPDATE ON job_queue
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The critical index for FOR UPDATE SKIP LOCKED polling.
-- Worker queries: WHERE status = 'PENDING' AND job_type = ?
-- Partial on PENDING only — completed and failed jobs
-- are never polled so excluding them keeps this index tiny.
CREATE INDEX idx_job_queue_polling
    ON job_queue(job_type, status, next_retry_at)
    WHERE status = 'PENDING';

-- Prevents two active sync jobs for the same user at the DB layer.
-- The API's 409 check is the first line of defence.
-- This index is the second — even if two requests slip through
-- simultaneously, only one INSERT will succeed.
CREATE UNIQUE INDEX idx_one_active_sync_per_user
    ON job_queue(user_id, job_type)
    WHERE job_type = 'BANK_SYNC'
      AND status IN ('PENDING', 'PROCESSING');