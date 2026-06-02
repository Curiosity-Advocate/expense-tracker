-- B1 — Bank integration: raw transaction store.
--
-- One row per bank transaction, captured verbatim from whichever source the
-- user imported from (CSV upload in v2.0; aggregator API in v3.0). The point
-- of this table is to be an unedited record of what the source delivered
-- (N7: tamper evidence on bank-imported data). Normalisation into the
-- expenses table happens later (B3).
--
-- Tamper evidence is a per-user hash chain. Each row stores prev_hash
-- (the predecessor's current_hash for the same user) and current_hash
-- (SHA-256 of prev_hash || jsonb payload || user_id || external_transaction_id).
-- The chain is computed in a BEFORE INSERT trigger so it holds regardless
-- of who is doing the writing (app, worker, future migrations, superuser).
--
-- Concurrent inserts for the same user are serialised by a per-user
-- pg_advisory_xact_lock inside the trigger — see V_n notes on Option C
-- in the B1 design discussion. SERIALIZABLE would be a heavier hammer
-- that locks unrelated traffic in the same transaction.

CREATE TABLE raw_bank_transactions (
    id                      UUID         PRIMARY KEY,
    user_id                 UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Discriminator: which format the row's payload is in. Drives B3's
    -- choice of parser. CHECK enforces an allow-list so a typo at import
    -- time can't poison the normalisation pipeline; new bank formats add
    -- a value here via a follow-up migration.
    source_format           VARCHAR(20)  NOT NULL CHECK (source_format IN (
        'csv_cba_v1', 'csv_anz_v1', 'csv_ubank_v1',
        'csv_amp_v1', 'csv_qudos_v1', 'csv_suncorp_v1'
    )),

    -- Source's identifier for the transaction. For CSV imports (which don't
    -- carry stable IDs), the import service computes this as a deterministic
    -- hash of date+amount+description+bank so re-uploading the same CSV is
    -- idempotent via the UNIQUE constraint below.
    external_transaction_id VARCHAR(100) NOT NULL,

    -- The full source payload for this transaction. JSONB so B3 can
    -- introspect fields without losing fidelity. For CSV: an object like
    -- { "parsed": {"date":..., "amount":..., "description":...},
    --   "raw_line": "..." } so we keep both the structured and verbatim view.
    raw_payload             JSONB        NOT NULL,

    fetched_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Chain link to the predecessor (this user's previous insert).
    -- NULL on the user's first row. Set by the BEFORE INSERT trigger;
    -- application code must not supply it.
    prev_hash               CHAR(64)     NULL,

    -- SHA-256 hex of the chain inputs. Set by the BEFORE INSERT trigger.
    current_hash            CHAR(64)     NOT NULL,

    -- Idempotency: re-syncing returns the same transaction; ON CONFLICT
    -- DO NOTHING at the application layer skips the second insert.
    UNIQUE (user_id, external_transaction_id),

    -- current_hash is globally unique because the input includes user_id
    -- and external_transaction_id; a collision would mean a SHA-256
    -- preimage attack or a real data duplicate.
    UNIQUE (current_hash)
);

-- Tail lookup for the hash-chain trigger: "most recent insert for this user".
-- Sorted on fetched_at DESC so the trigger's LIMIT 1 is O(log n).
CREATE INDEX idx_raw_bank_transactions_user_tail
    ON raw_bank_transactions (user_id, fetched_at DESC);

-- ── Hash-chain trigger ───────────────────────────────────────────────────────
-- Computes prev_hash and current_hash on INSERT. The per-user advisory lock
-- serialises concurrent inserts for the same user without blocking other
-- users. hashtextextended is 64-bit; collisions across user_ids would cause
-- two unrelated users to briefly wait on each other (correctness preserved,
-- negligible latency hit at our scale).

CREATE OR REPLACE FUNCTION compute_raw_bank_transaction_hash()
RETURNS TRIGGER AS $$
DECLARE
    previous_hash CHAR(64);
    chain_input   TEXT;
BEGIN
    IF NEW.prev_hash IS NOT NULL OR NEW.current_hash IS NOT NULL THEN
        RAISE EXCEPTION
            'raw_bank_transactions: prev_hash / current_hash are computed by '
            'the BEFORE INSERT trigger and must not be supplied by the caller';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.user_id::text, 0));

    SELECT current_hash
      INTO previous_hash
      FROM raw_bank_transactions
     WHERE user_id = NEW.user_id
     ORDER BY fetched_at DESC, current_hash DESC
     LIMIT 1;

    NEW.prev_hash := previous_hash;  -- NULL for the user's first row

    chain_input := COALESCE(previous_hash, '')
                || NEW.raw_payload::text
                || NEW.user_id::text
                || NEW.external_transaction_id;

    NEW.current_hash := encode(sha256(chain_input::bytea), 'hex');

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_raw_bank_transactions_hash_chain
    BEFORE INSERT ON raw_bank_transactions
    FOR EACH ROW EXECUTE FUNCTION compute_raw_bank_transaction_hash();

-- ── Append-only immutability ────────────────────────────────────────────────
-- Reuses the V21 enforce_immutability_except function with an empty allow-list:
-- every column is locked once inserted. Tamper evidence is only meaningful
-- if rows themselves can't be edited.

CREATE TRIGGER trg_raw_bank_transactions_immutability
    BEFORE UPDATE ON raw_bank_transactions
    FOR EACH ROW EXECUTE FUNCTION enforce_immutability_except();

-- DELETE is blocked too; rows belong to the chain forever.
CREATE OR REPLACE FUNCTION block_delete_on_raw_bank_transactions()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'raw_bank_transactions: DELETE forbidden — append-only table';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_raw_bank_transactions_no_delete
    BEFORE DELETE ON raw_bank_transactions
    FOR EACH ROW EXECUTE FUNCTION block_delete_on_raw_bank_transactions();

-- ── RLS ──────────────────────────────────────────────────────────────────────
-- Standard single-clause: a user sees their own raw transactions only.
-- The worker (superuser) bypasses RLS for cross-user maintenance reads.

ALTER TABLE raw_bank_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE raw_bank_transactions FORCE  ROW LEVEL SECURITY;

CREATE POLICY user_isolation ON raw_bank_transactions AS RESTRICTIVE
    USING (user_id = current_setting('app.current_user_id')::uuid);
