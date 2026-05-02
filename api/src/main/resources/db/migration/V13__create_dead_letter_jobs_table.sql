-- Dead letter table — jobs that have exhausted all retry cycles land here.
-- A dead letter record is created after 3 failed cycles with 24h gaps between them.
-- Manual re-trigger is required via POST /api/v1/system/dead-letters/{id}/retry.
--
-- retry_job_id is populated when a manual retry is triggered —
-- links back to the new job_queue entry created for the retry.
CREATE TABLE dead_letter_jobs (
    id                  UUID        PRIMARY KEY,
    original_job_id     UUID        NOT NULL REFERENCES job_queue(id),
    job_type            VARCHAR(50) NOT NULL,
    user_id             UUID        NULL REFERENCES users(id),
    payload             JSONB       NOT NULL,
    cycles_attempted    INT         NOT NULL,
    last_error          TEXT        NOT NULL,
    status              VARCHAR(30) NOT NULL DEFAULT 'AWAITING_MANUAL_RETRY',
    retried_at          TIMESTAMPTZ NULL,
    retry_job_id        UUID        NULL REFERENCES job_queue(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_dead_letter_status
        CHECK (status IN ('AWAITING_MANUAL_RETRY', 'RETRIED', 'RESOLVED'))
);

CREATE INDEX idx_dead_letter_user
    ON dead_letter_jobs(user_id, status)
    WHERE status = 'AWAITING_MANUAL_RETRY';