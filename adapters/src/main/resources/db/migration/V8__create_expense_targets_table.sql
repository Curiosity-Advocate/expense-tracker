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
    -- Lower bound matches expenses.chk_date_partition_lower (V5). A target for
    -- a year before any expense partition exists could never compute "spent".
    -- No upper bound — long-term future planning targets are valid user input.
    CONSTRAINT chk_period_year_lower CHECK (period_year >= 2023),
    CONSTRAINT chk_period_month   CHECK (period_month BETWEEN 1 AND 12)
);

-- Serves two purposes:
--   1. Unique constraint — prevents two active targets of the same scope for the same period
--   2. Prefix lookup index — covers queries filtering on (user_id),
--      (user_id, period_year), or (user_id, period_year, period_month).
--      No separate non-unique index needed because B-trees support left-prefix matches.
-- WHERE deleted_at IS NULL — soft-deleted targets don't block replacements
--                           and are excluded from active-target lookups.
CREATE UNIQUE INDEX uq_one_active_target_per_period
    ON expense_targets (user_id, period_year, period_month, target_type)
    WHERE deleted_at IS NULL;

CREATE TRIGGER trg_targets_set_updated_at
    BEFORE UPDATE ON expense_targets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_targets_lock_created_at
    BEFORE UPDATE ON expense_targets
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
