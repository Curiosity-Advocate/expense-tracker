-- S5 — Row-level audit trail.
--
-- Adds created_by + modified_by UUID columns to every user-scoped business table
-- (the ones data-model.md previously claimed all tables had — this migration
-- makes the doc true). Two new shared trigger functions handle population
-- and immutability. See ADR-0017 for the design rationale and how D3 will
-- plug in via app.acting_user_id.
--
-- Tables included: users, bank_accounts, categories, expenses (partitioned —
-- triggers propagate to all current and future partitions), expense_categories,
-- expense_targets, target_categories.
--
-- Tables intentionally EXCLUDED:
--   * user_login_failures   — security log; row IS the audit by design
--   * refresh_tokens         — security primitive; immutability rules in V21
--                              conflict with the audit-trigger UPDATE semantics
--   * expense_idempotency_keys — system-managed cache; never user-edited
--   * banks / partition_registry / job_execution_state — system-wide,
--                              no user concept

-- ── Shared trigger functions ─────────────────────────────────────────────────

-- Reads acting_user_id (set by D3's delegation filter — currently always NULL
-- since D3 isn't shipped) and falls back to current_user_id (the data owner,
-- which IS the actor for non-delegated flows). NULLIF handles the empty-string
-- case that current_setting(..., true) returns when the GUC is unset.
--
-- On INSERT both columns are set. On UPDATE only modified_by changes; if no
-- actor is set in the session, the OLD value is preserved rather than nulled
-- (so future scheduled jobs that touch user-scoped rows don't erase audit).
CREATE OR REPLACE FUNCTION set_audit_user()
RETURNS TRIGGER AS $$
DECLARE
    actor UUID;
BEGIN
    actor := COALESCE(
        NULLIF(current_setting('app.acting_user_id',  true), '')::uuid,
        NULLIF(current_setting('app.current_user_id', true), '')::uuid
    );

    IF TG_OP = 'INSERT' THEN
        NEW.created_by  = actor;
        NEW.modified_by = actor;
    ELSIF TG_OP = 'UPDATE' THEN
        IF actor IS NOT NULL THEN
            NEW.modified_by = actor;
        ELSE
            NEW.modified_by = OLD.modified_by;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Mirrors lock_created_at from V1. The set_audit_user trigger only writes
-- created_by on INSERT, but this is the DB-enforced backstop against rogue
-- UPDATEs that attempt to change it.
CREATE OR REPLACE FUNCTION lock_created_by()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'created_by is immutable — it cannot be changed after insert';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── users ────────────────────────────────────────────────────────────────────
-- Self-referential FK is allowed and matches the "admin creates user" future
-- case. For self-registration on the setup pool the trigger sees no session
-- variable → created_by = NULL.

ALTER TABLE users ADD COLUMN created_by  UUID NULL REFERENCES users(id);
ALTER TABLE users ADD COLUMN modified_by UUID NULL REFERENCES users(id);

CREATE TRIGGER trg_users_set_audit_user
    BEFORE INSERT OR UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_users_lock_created_by
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── bank_accounts ────────────────────────────────────────────────────────────

ALTER TABLE bank_accounts ADD COLUMN created_by  UUID NULL REFERENCES users(id);
ALTER TABLE bank_accounts ADD COLUMN modified_by UUID NULL REFERENCES users(id);

CREATE TRIGGER trg_bank_accounts_set_audit_user
    BEFORE INSERT OR UPDATE ON bank_accounts
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_bank_accounts_lock_created_by
    BEFORE UPDATE ON bank_accounts
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── categories ───────────────────────────────────────────────────────────────

ALTER TABLE categories ADD COLUMN created_by  UUID NULL REFERENCES users(id);
ALTER TABLE categories ADD COLUMN modified_by UUID NULL REFERENCES users(id);

CREATE TRIGGER trg_categories_set_audit_user
    BEFORE INSERT OR UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_categories_lock_created_by
    BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── expenses (partitioned) ───────────────────────────────────────────────────
-- ALTER on the partitioned parent propagates the column to every partition.
-- BEFORE row triggers attached to the partitioned parent fire on every
-- partition (Postgres 13+ behaviour; we're on 16).

ALTER TABLE expenses ADD COLUMN created_by  UUID NULL REFERENCES users(id);
ALTER TABLE expenses ADD COLUMN modified_by UUID NULL REFERENCES users(id);

CREATE TRIGGER trg_expenses_set_audit_user
    BEFORE INSERT OR UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_expenses_lock_created_by
    BEFORE UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── expense_categories ───────────────────────────────────────────────────────

ALTER TABLE expense_categories ADD COLUMN created_by  UUID NULL REFERENCES users(id);
ALTER TABLE expense_categories ADD COLUMN modified_by UUID NULL REFERENCES users(id);

CREATE TRIGGER trg_expense_categories_set_audit_user
    BEFORE INSERT OR UPDATE ON expense_categories
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_expense_categories_lock_created_by
    BEFORE UPDATE ON expense_categories
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── expense_targets ──────────────────────────────────────────────────────────

ALTER TABLE expense_targets ADD COLUMN created_by  UUID NULL REFERENCES users(id);
ALTER TABLE expense_targets ADD COLUMN modified_by UUID NULL REFERENCES users(id);

CREATE TRIGGER trg_expense_targets_set_audit_user
    BEFORE INSERT OR UPDATE ON expense_targets
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_expense_targets_lock_created_by
    BEFORE UPDATE ON expense_targets
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── target_categories ────────────────────────────────────────────────────────

ALTER TABLE target_categories ADD COLUMN created_by  UUID NULL REFERENCES users(id);
ALTER TABLE target_categories ADD COLUMN modified_by UUID NULL REFERENCES users(id);

CREATE TRIGGER trg_target_categories_set_audit_user
    BEFORE INSERT OR UPDATE ON target_categories
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_target_categories_lock_created_by
    BEFORE UPDATE ON target_categories
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();
