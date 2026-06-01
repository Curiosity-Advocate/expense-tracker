-- B1.3 — Bank-integration: CSV import connection per account.
--
-- Designed for maximum isolation (see ADR-0020): each import source owns its
-- own table. v2.0 ships csv_import_connections only. v3.0 may add a sibling
-- basiq_import_connections (or similar) without touching this table or
-- bank_accounts. The "at most one active source per bank_account" invariant
-- is enforced by a cross-source trigger that arrives with the second source —
-- not needed yet while only CSV exists.
--
-- bank_account_id is both PK and FK to bank_accounts(id), enforcing 1:1 —
-- a bank_account has at most one CSV import config. Switching off CSV is
-- a delete-row operation; switching back is an insert.

-- ── Account_type: widen and add CREDIT_CARD ─────────────────────────────────
-- V3 declared account_type VARCHAR(10), which is exactly one short of fitting
-- 'CREDIT_CARD' (11 chars). Widen to 20 to give headroom for future values
-- (OFFSET, INVESTMENT, etc.) without another migration each time.

ALTER TABLE bank_accounts ALTER COLUMN account_type TYPE VARCHAR(20);

ALTER TABLE bank_accounts DROP CONSTRAINT chk_account_type;
ALTER TABLE bank_accounts ADD CONSTRAINT chk_account_type
    CHECK (account_type IN ('CASH', 'CRYPTO', 'BANK', 'CREDIT_CARD'));

-- ── csv_import_connections ──────────────────────────────────────────────────

CREATE TABLE csv_import_connections (
    -- 1:1 with bank_accounts. Deleting the account cascades the config away.
    bank_account_id  UUID         PRIMARY KEY REFERENCES bank_accounts(id) ON DELETE CASCADE,

    -- Denormalised from bank_accounts so RLS can be a single-column policy
    -- (avoids a subquery into bank_accounts on every row read).
    user_id          UUID         NOT NULL REFERENCES users(id),

    -- Which bank this CSV connection is for. The B1.4 import service picks
    -- the actual parser version at upload time using (bank_id, exportedOnDate)
    -- so format-revision changes don't require updating this row — see
    -- ADR-0020 for the date-dispatched parser model. The parser stamps its
    -- versionTag (e.g. "csv_cba_v1") into raw_bank_transactions.source_format
    -- on each persisted row, keeping per-row provenance discoverable.
    -- Naming: kept as bank_id (rather than bank_code) for consistency with
    -- the rest of the schema's *_id convention, even though there's no
    -- banks table — values are an enum encoded as VARCHAR. New banks add
    -- a value to both this CHECK and raw_bank_transactions.source_format's
    -- CHECK in a single migration.
    bank_id          VARCHAR(20)  NOT NULL CHECK (bank_id IN (
        'cba', 'anz', 'ubank', 'amp', 'qudos', 'suncorp'
    )),

    -- User's bookmark for the bank's CSV export page. UI surfaces it when
    -- the user clicks "Import CSV" so they don't have to navigate every time.
    csv_export_url   VARCHAR(500) NULL,

    -- Set by the import service on every successful import. Drives the
    -- 7-day rate limit in B1.4 — failed imports do NOT update this.
    last_imported_at TIMESTAMPTZ  NULL,

    -- MAX(transaction_date) seen across all successful imports for this
    -- account. UX hint: "you've already imported up to X — this CSV starts
    -- earlier." Not a rate-limit input; just informational.
    last_date_to     DATE         NULL,

    -- Standard audit (S5 conventions).
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       UUID         NULL REFERENCES users(id),
    modified_by      UUID         NULL REFERENCES users(id)
);

-- Standard timestamp triggers from V1.
CREATE TRIGGER trg_csv_import_connections_set_updated_at
    BEFORE UPDATE ON csv_import_connections
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_csv_import_connections_lock_created_at
    BEFORE UPDATE ON csv_import_connections
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();

-- Audit-user triggers from V23.
CREATE TRIGGER trg_csv_import_connections_set_audit_user
    BEFORE INSERT OR UPDATE ON csv_import_connections
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_csv_import_connections_lock_created_by
    BEFORE UPDATE ON csv_import_connections
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── RLS ──────────────────────────────────────────────────────────────────────
-- Standard single-clause on the denormalised user_id.

ALTER TABLE csv_import_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE csv_import_connections FORCE  ROW LEVEL SECURITY;

CREATE POLICY user_isolation ON csv_import_connections AS RESTRICTIVE
    USING (user_id = current_setting('app.current_user_id')::uuid);
