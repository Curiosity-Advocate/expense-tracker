-- S4 — Refresh-token rotation infrastructure.
--
-- Stores one row per refresh token issuance. The same row is also the
-- revocation record once revoked_at + revoke_reason are set. Strict
-- append-only semantics enforced by triggers below: no column may change
-- except revoked_at / revoke_reason, and even those are set-once.
--
-- The table is single-source-of-truth for active sessions. "Active" means
-- WHERE revoked_at IS NULL AND expires_at > NOW(). No JOIN required.
--
-- revoked_tokens (ADR-0009) is superseded by this design: 15-minute access
-- tokens make per-request revocation lookups unnecessary, and refresh-token
-- rotation provides the compromise-detection that revocation tables can't.

-- ── Reusable trigger functions ───────────────────────────────────────────────
-- Generic enough to be applied to future tables (sudo_tokens in D2,
-- merge_audit in B5). The allow-list of mutable columns lives on the
-- CREATE TRIGGER line, not buried in the function body. Adding a new
-- column to a table protected by this trigger leaves it immutable by
-- default — fail-closed.

CREATE OR REPLACE FUNCTION enforce_immutability_except()
RETURNS TRIGGER AS $$
DECLARE
    old_filtered JSONB := to_jsonb(OLD);
    new_filtered JSONB := to_jsonb(NEW);
    col          TEXT;
BEGIN
    FOREACH col IN ARRAY TG_ARGV LOOP
        old_filtered := old_filtered - col;
        new_filtered := new_filtered - col;
    END LOOP;

    IF old_filtered IS DISTINCT FROM new_filtered THEN
        RAISE EXCEPTION
            'Table % UPDATE forbidden — only these columns may change: %',
            TG_TABLE_NAME, array_to_string(TG_ARGV, ', ');
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Once a column is non-NULL it cannot change again. The dynamic SQL
-- compares the column by name via format() so the same function works
-- for any column on any table.
CREATE OR REPLACE FUNCTION enforce_set_once_column()
RETURNS TRIGGER AS $$
DECLARE
    col     TEXT := TG_ARGV[0];
    old_val TEXT;
    new_val TEXT;
BEGIN
    EXECUTE format('SELECT ($1).%I::TEXT, ($2).%I::TEXT', col, col)
        INTO old_val, new_val USING OLD, NEW;

    IF old_val IS NOT NULL AND new_val IS DISTINCT FROM old_val THEN
        RAISE EXCEPTION
            'Table %, column %: set-once — cannot be modified or unset once set',
            TG_TABLE_NAME, col;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── refresh_tokens table ─────────────────────────────────────────────────────

CREATE TABLE refresh_tokens (
    token_hash         VARCHAR(64)   PRIMARY KEY,
    user_id            UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Set on first login. Copied unchanged across rotations so the chain has a
    -- hard expiry that no amount of rotation can extend (max-session cap).
    session_started_at TIMESTAMPTZ   NOT NULL,

    -- Always = session_started_at + 7 days. Materialised so cleanup can index it.
    expires_at         TIMESTAMPTZ   NOT NULL,

    issued_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- The previous chain link's hash. NULL on the first issuance (fresh login).
    -- Self-FK; we never DELETE, only soft-revoke, so the FK is safe.
    rotated_from       VARCHAR(64)   NULL REFERENCES refresh_tokens(token_hash),

    -- Revocation columns. NULL means active. Both must be set together.
    revoked_at         TIMESTAMPTZ   NULL,
    revoke_reason      VARCHAR(20)   NULL,

    CONSTRAINT chk_revoke_pair CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    ),
    CONSTRAINT chk_revoke_reason CHECK (
        revoke_reason IS NULL
        OR revoke_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED')
    )
);

-- Partial index: "find this user's currently-active refresh tokens." Used by
-- the chain-revocation path when reuse is detected. Only indexes unrevoked
-- rows, so it stays small even as old rows accumulate before cleanup.
CREATE INDEX idx_refresh_tokens_user_active
    ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- Index for the nightly cleanup job that purges expired rows.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- ── Immutability triggers ────────────────────────────────────────────────────
-- Whitelist exactly the two columns the application is allowed to change.
-- Any other column — present today or added by a future migration — is
-- immutable by default.

CREATE TRIGGER trg_refresh_tokens_immutability
    BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW
    EXECUTE FUNCTION enforce_immutability_except('revoked_at', 'revoke_reason');

-- Even within the allowed columns, revocation is set-once. An attacker who
-- somehow reaches an UPDATE path cannot un-revoke a token or change the reason.
CREATE TRIGGER trg_refresh_tokens_revoked_at_set_once
    BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW
    EXECUTE FUNCTION enforce_set_once_column('revoked_at');

CREATE TRIGGER trg_refresh_tokens_revoke_reason_set_once
    BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW
    EXECUTE FUNCTION enforce_set_once_column('revoke_reason');

-- ── RLS ──────────────────────────────────────────────────────────────────────
-- Same pattern as every other user-scoped table. The /refresh endpoint sets
-- app.current_user_id from the JWT in the access token (or from the refresh
-- token's user_id once looked up via the setup pool — see service layer).

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;

CREATE POLICY user_isolation ON refresh_tokens AS RESTRICTIVE
    USING (user_id = current_setting('app.current_user_id')::uuid);

-- ── Grants ──────────────────────────────────────────────────────────────────
-- Setup pool writes the row on login and looks it up on refresh. The app pool
-- already gets ALL PRIVILEGES on new tables via init-db.sh's default-privileges
-- grant — no explicit GRANT needed for expense_app.

GRANT SELECT, INSERT, UPDATE ON refresh_tokens TO expense_setup;
