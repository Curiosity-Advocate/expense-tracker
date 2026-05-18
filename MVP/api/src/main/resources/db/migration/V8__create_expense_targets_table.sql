-- One row per target. The scope (which categories count) lives in target_categories.
-- target_type drives how target_categories is interpreted:
--   CATEGORY      → exactly one INCLUSIVE entry
--   MULTI_CATEGORY → two or more INCLUSIVE entries
--   TOTAL         → zero or more EXCLUSIVE carve-outs, no INCLUSIVE
CREATE TABLE expense_targets (
    id           UUID            PRIMARY KEY,
    user_id      UUID            NOT NULL REFERENCES users(id),
    target_type  VARCHAR(20)     NOT NULL,
    amount       NUMERIC(12,2)   NOT NULL,
    period_year  INTEGER         NOT NULL,
    period_month INTEGER         NOT NULL,
    deleted_at   TIMESTAMPTZ     NULL,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_target_type    CHECK (target_type IN ('CATEGORY','MULTI_CATEGORY','TOTAL')),
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_period_month   CHECK (period_month BETWEEN 1 AND 12)
);

-- Prevents two active targets of the same scope for the same period.
-- WHERE deleted_at IS NULL — soft-deleted targets don't block replacements.
CREATE UNIQUE INDEX uq_one_active_target_per_period
    ON expense_targets (user_id, period_year, period_month, target_type)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_targets_user_period
    ON expense_targets (user_id, period_year, period_month)
    WHERE deleted_at IS NULL;

CREATE TRIGGER trg_targets_set_updated_at
    BEFORE UPDATE ON expense_targets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_targets_lock_created_at
    BEFORE UPDATE ON expense_targets
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
