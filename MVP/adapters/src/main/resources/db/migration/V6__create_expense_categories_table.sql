-- Junction table between an expense and its categories.
-- weight_amount stores the pre-computed even split at write time — e.g. a $100
-- expense across 4 categories stores four rows with $25.00 each.
-- Storing the computed weight eliminates recomputation on every aggregation query.
--
-- user_id is denormalised from the expense for direct RLS enforcement.
-- Safe to denormalise because expense ownership never changes after creation.
CREATE TABLE expense_categories (
    expense_id      UUID            NOT NULL,
    expense_date    DATE            NOT NULL,
    category_id     UUID            NOT NULL REFERENCES categories(id),
    user_id         UUID            NOT NULL REFERENCES users(id),
    weight_amount   NUMERIC(12,2)   NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_weight_positive CHECK (weight_amount > 0),

    PRIMARY KEY (expense_id, expense_date, category_id),
    FOREIGN KEY (expense_id, expense_date) REFERENCES expenses(id, expense_date)
);

CREATE TRIGGER trg_expense_categories_set_updated_at
    BEFORE UPDATE ON expense_categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_expense_categories_lock_created_at
    BEFORE UPDATE ON expense_categories
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
