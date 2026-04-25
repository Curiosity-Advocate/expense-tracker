-- Per-category per-month targets and total monthly targets.
-- target_type drives which fields are required:
--   CATEGORY → category_id must be present
--   TOTAL    → category_id must be NULL
-- Both rules enforced at DB layer via chk_category_required_for_type.
CREATE TABLE expense_targets (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id),
    target_type     VARCHAR(20)     NOT NULL,
    category_id     UUID            NULL REFERENCES categories(id),
    amount          NUMERIC(12,2)   NOT NULL,
    period_type     VARCHAR(20)     NOT NULL DEFAULT 'MONTHLY',
    period_year     INT             NOT NULL,
    period_month    INT             NOT NULL,
    deleted_at      TIMESTAMPTZ     NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_target_type
        CHECK (target_type IN ('CATEGORY', 'TOTAL')),

    -- Enforces the relationship between target_type and category_id.
    -- A CATEGORY target without a category_id is meaningless.
    -- A TOTAL target with a category_id is contradictory.
    CONSTRAINT chk_category_required_for_type
        CHECK (
            (target_type = 'CATEGORY' AND category_id IS NOT NULL) OR
            (target_type = 'TOTAL'    AND category_id IS NULL)
        ),

    CONSTRAINT chk_amount_positive
        CHECK (amount > 0),

    CONSTRAINT chk_period_month
        CHECK (period_month BETWEEN 1 AND 12),

    -- Prevents two active DINING targets for April 2026.
    -- WHERE deleted_at IS NULL means soft-deleted targets
    -- don't block creating a replacement for the same period.
    CONSTRAINT uq_one_target_per_category_per_period
        UNIQUE NULLS NOT DISTINCT (
            user_id, target_type, category_id,
            period_year, period_month
        ) WHERE (deleted_at IS NULL)
);

CREATE TRIGGER trg_expense_targets_set_updated_at
    BEFORE UPDATE ON expense_targets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_targets_user_period
    ON expense_targets(user_id, period_year, period_month)
    WHERE deleted_at IS NULL;