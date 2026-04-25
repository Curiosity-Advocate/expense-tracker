-- Junction table: one expense → many categories.
-- expense_date is denormalised here because the FK must include
-- the full PK of the partitioned expenses table (id, expense_date).
-- This is PostgreSQL's requirement, not a design choice.
-- The application always has expense_date when writing categories
-- because it just created or fetched the parent expense.
CREATE TABLE expense_categories (
    expense_id      UUID            NOT NULL,
    expense_date    DATE            NOT NULL,
    category_id     UUID            NOT NULL REFERENCES categories(id),
    weight_amount   NUMERIC(12,2)   NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    PRIMARY KEY (expense_id, expense_date, category_id),
    FOREIGN KEY (expense_id, expense_date)
        REFERENCES expenses(id, expense_date)
);

CREATE INDEX idx_expense_categories_category
    ON expense_categories(category_id, expense_date);