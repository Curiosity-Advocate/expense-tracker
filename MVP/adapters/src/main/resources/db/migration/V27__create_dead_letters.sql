-- B1 — Dead-letter store.
--
-- Pulled forward from B7 because B1's CSV-import endpoint needs somewhere
-- to record per-row parse / persist failures from day one. B7 ships the
-- operator API (GET list, POST retry) on top of this table; the table
-- itself belongs in B1.
--
-- Generic schema (job_type discriminator + JSONB payload) so the same
-- table serves B1 (CSV_IMPORT failures), B3 (NORMALISE failures), and any
-- future job that needs a manual-intervention surface (e.g. BANK_SYNC if
-- v3.0 adds an aggregator). Operators read per-job-type via the
-- discriminator; the payload schema is the contract between writer and
-- retry handler.

CREATE TABLE dead_letters (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Discriminator. CHECK enforces the allow-list; new job types add a
    -- value here via a follow-up migration so the writer and retry
    -- handler stay in lockstep with the schema.
    job_type      VARCHAR(50)  NOT NULL CHECK (job_type IN ('CSV_IMPORT')),

    -- Inputs to the failed job, captured so a retry can replay the work.
    -- For CSV_IMPORT this is { "bankAccountId": "...", "bankId": "...",
    -- "exportedOnDate": "...", "rowNumber": N, "rawLine": "...",
    -- "parserVersionTag": "csv_cba_v1" } — enough for an operator to
    -- understand what was being parsed and trigger a re-parse with a
    -- corrected exportedOnDate or after a parser fix.
    payload       JSONB        NOT NULL,

    -- Exception classification for filtering / metrics. error_class is
    -- the fully-qualified exception name; error_message is short and
    -- meant for operators (full stack traces go to structured logs).
    error_class   VARCHAR(255) NOT NULL,
    error_message TEXT         NOT NULL,

    -- Attempt counter. Incremented by the retry endpoint when it
    -- re-tries this row. Starts at 1 (the original failed attempt).
    attempts      INT          NOT NULL DEFAULT 1 CHECK (attempts > 0),

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Set when an operator hits the retry endpoint, even if the retry
    -- itself fails (so we can see "has it ever been retried" separately
    -- from "is it resolved").
    retried_at    TIMESTAMPTZ  NULL,

    -- Set when an operator marks the row resolved — either because the
    -- retry succeeded or because the failure is no longer relevant.
    -- NULL = still-open. Future B7 may add a resolution_reason column.
    resolved_at   TIMESTAMPTZ  NULL,

    CONSTRAINT chk_retried_at_after_created  CHECK (retried_at  IS NULL OR retried_at  >= created_at),
    CONSTRAINT chk_resolved_at_after_created CHECK (resolved_at IS NULL OR resolved_at >= created_at)
);

-- Operator queries: "show me unresolved dead letters for this job type."
-- Partial index keeps the on-disk footprint proportional to open rows
-- only — resolved rows age out of the index but stay in the table.
CREATE INDEX idx_dead_letters_open_by_type
    ON dead_letters (job_type, created_at DESC)
    WHERE resolved_at IS NULL;

CREATE INDEX idx_dead_letters_user_open
    ON dead_letters (user_id, created_at DESC)
    WHERE resolved_at IS NULL;

-- ── Immutability ────────────────────────────────────────────────────────────
-- The original failure record is immutable; only the operator-controlled
-- fields (attempts, retried_at, resolved_at) may change post-insert.
-- enforce_immutability_except is the V21 function.

CREATE TRIGGER trg_dead_letters_immutability
    BEFORE UPDATE ON dead_letters
    FOR EACH ROW
    EXECUTE FUNCTION enforce_immutability_except('attempts', 'retried_at', 'resolved_at');

-- resolved_at is set-once: an operator cannot un-resolve a row. If a
-- resolution turns out to be wrong, the right move is a fresh dead-letter
-- record, not silently flipping this one back open.
CREATE TRIGGER trg_dead_letters_resolved_at_set_once
    BEFORE UPDATE ON dead_letters
    FOR EACH ROW
    EXECUTE FUNCTION enforce_set_once_column('resolved_at');

-- DELETE is blocked. Dead letters are kept for forensic value; an operator
-- "deleting" a row means resolving it via the API.
CREATE OR REPLACE FUNCTION block_delete_on_dead_letters()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'dead_letters: DELETE forbidden — resolve via the retry endpoint instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dead_letters_no_delete
    BEFORE DELETE ON dead_letters
    FOR EACH ROW EXECUTE FUNCTION block_delete_on_dead_letters();

-- ── RLS ──────────────────────────────────────────────────────────────────────
-- Per-user isolation. The worker (superuser) bypasses RLS for any cross-user
-- operational queries. The B1 sync endpoint runs on the app pool, so writes
-- happen under app.current_user_id = the syncing user.

ALTER TABLE dead_letters ENABLE ROW LEVEL SECURITY;
ALTER TABLE dead_letters FORCE  ROW LEVEL SECURITY;

CREATE POLICY user_isolation ON dead_letters AS RESTRICTIVE
    USING (user_id = current_setting('app.current_user_id')::uuid);
