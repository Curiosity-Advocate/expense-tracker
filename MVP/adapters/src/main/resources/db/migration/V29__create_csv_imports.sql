-- B1.4 — CSV import job state for async processing.
--
-- Each row tracks one CSV upload from submission through to completion.
-- The upload endpoint inserts a PENDING row and triggers the async
-- processor; the processor moves it through RUNNING → COMPLETED (or FAILED).
-- The status endpoint reads from this table.
--
-- raw_csv_bytes carries the uploaded file across the async boundary so
-- the processor doesn't depend on the upload request still being in flight.
-- Bytes are zeroed (and raw_csv_bytes_deleted_at set) immediately on
-- COMPLETED to bound the table's size. We never delete the row — it's a
-- historical record visible via the status endpoint.
--
-- Startup recovery: on API restart, any rows left in RUNNING with a stale
-- started_at get reset to PENDING and re-kicked-off. That scan runs without
-- a user context (no JWT at startup) so it goes through the setup pool
-- (BYPASSRLS); see the GRANT at the end of this migration.

CREATE TABLE csv_imports (
    id                       UUID         PRIMARY KEY,

    -- FK back to the connection. CASCADE so tearing down a CSV connection
    -- also drops its import history.
    bank_account_id          UUID         NOT NULL REFERENCES csv_import_connections(bank_account_id) ON DELETE CASCADE,

    -- Denormalised from the connection for RLS (single-column policy).
    user_id                  UUID         NOT NULL REFERENCES users(id),

    -- Lifecycle. Transitions:
    --   PENDING  → RUNNING    (when @Async picks it up; sets started_at)
    --   RUNNING  → COMPLETED  (terminal, success; sets completed_at, clears bytes)
    --   RUNNING  → FAILED     (terminal, error_message set; sets completed_at)
    --   RUNNING  → PENDING    (startup recovery resets stale RUNNING rows)
    status                   VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),

    -- The upload's exportedOnDate (or NOW() if omitted). Drives parser
    -- dispatch at processing time.
    exported_on_date         DATE         NOT NULL,

    -- The parser the dispatcher picked. Stamped here for forensics; surfaced
    -- in the status response as exportedAfterDate. Matches what the parser
    -- writes into raw_bank_transactions.source_format.
    parser_version_tag       VARCHAR(20)  NOT NULL,

    -- The verbatim uploaded file. Non-null on insert; UPDATEd to an empty
    -- bytea (\\x) when status reaches COMPLETED/FAILED — see
    -- raw_csv_bytes_deleted_at. Sized for the 10MB upload cap; BYTEA in
    -- PostgreSQL is bounded by 1GB so we have headroom.
    raw_csv_bytes            BYTEA        NOT NULL,
    raw_csv_bytes_deleted_at TIMESTAMPTZ  NULL,

    -- Counters, updated incrementally by the processor batch-by-batch.
    -- Reset to 0 on a startup-recovery retry (the processor doesn't know
    -- which inserts from a previous attempt are "already counted").
    imported_count           INT          NOT NULL DEFAULT 0,
    deduped_count            INT          NOT NULL DEFAULT 0,
    parse_error_count        INT          NOT NULL DEFAULT 0,
    last_processed_row       INT          NOT NULL DEFAULT 0,

    -- Populated only when status = FAILED.
    error_message            TEXT         NULL,

    -- Lifecycle timestamps.
    submitted_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at               TIMESTAMPTZ  NULL,
    completed_at             TIMESTAMPTZ  NULL,

    -- Standard audit columns (S5).
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by               UUID         NULL REFERENCES users(id),
    modified_by              UUID         NULL REFERENCES users(id),

    CONSTRAINT chk_terminal_has_completed_at CHECK (
        status NOT IN ('COMPLETED', 'FAILED') OR completed_at IS NOT NULL
    ),
    CONSTRAINT chk_non_pending_has_started_at CHECK (
        status = 'PENDING' OR started_at IS NOT NULL
    ),
    CONSTRAINT chk_failed_has_error CHECK (
        status <> 'FAILED' OR error_message IS NOT NULL
    ),
    CONSTRAINT chk_bytes_deleted_only_on_terminal CHECK (
        raw_csv_bytes_deleted_at IS NULL OR status IN ('COMPLETED', 'FAILED')
    )
);

-- Standard timestamp + audit triggers, matching every other user-scoped
-- business table.
CREATE TRIGGER trg_csv_imports_set_updated_at
    BEFORE UPDATE ON csv_imports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_csv_imports_lock_created_at
    BEFORE UPDATE ON csv_imports
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();

CREATE TRIGGER trg_csv_imports_set_audit_user
    BEFORE INSERT OR UPDATE ON csv_imports
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_csv_imports_lock_created_by
    BEFORE UPDATE ON csv_imports
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── Indexes ─────────────────────────────────────────────────────────────────
-- Partial index for startup recovery: "find RUNNING rows still going stale".
-- Only RUNNING rows live in this index, so it stays tiny.
CREATE INDEX idx_csv_imports_running ON csv_imports (started_at)
    WHERE status = 'RUNNING';

-- Partial index for the rate-limit check on upload: "any RUNNING or recently-
-- COMPLETED imports for this connection?". Sorted DESC so LIMIT 1 is O(log n).
CREATE INDEX idx_csv_imports_recent_per_connection
    ON csv_imports (bank_account_id, completed_at DESC NULLS FIRST)
    WHERE status IN ('RUNNING', 'COMPLETED');

-- ── RLS ─────────────────────────────────────────────────────────────────────
-- Single-clause on the denormalised user_id. Status endpoint queries flow
-- through here; startup recovery bypasses via the setup pool grant below.
ALTER TABLE csv_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE csv_imports FORCE  ROW LEVEL SECURITY;

CREATE POLICY user_isolation ON csv_imports AS RESTRICTIVE
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- ── Grants for startup recovery ─────────────────────────────────────────────
-- The recovery scan on API startup runs without a user context, so it can't
-- read csv_imports via the RLS-enforced app pool. The expense_setup role
-- (BYPASSRLS, used elsewhere for register/login/setup) gets SELECT + UPDATE
-- on this table. Recovery uses setupJdbcTemplate to: (a) find stale RUNNING
-- rows, (b) reset them to PENDING + clear started_at, (c) trigger the @Async
-- processor for each. The actual import work then happens on the app pool
-- with app.current_user_id set from the row's user_id.
GRANT SELECT, UPDATE ON csv_imports TO expense_setup;
