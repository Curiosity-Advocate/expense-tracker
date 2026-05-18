-- Partitioned by expense_date (RANGE). One partition per calendar year.
-- Composite PK (id, expense_date) is required by PostgreSQL when the partition
-- key must be part of the primary key — this is why single-expense lookups
-- require BOTH id and expense_date as API parameters.
CREATE TABLE expenses (
    id              UUID            NOT NULL,
    user_id         UUID            NOT NULL REFERENCES users(id),
    amount          NUMERIC(12,2)   NOT NULL,
    merchant_name   VARCHAR(255)    NOT NULL,
    expense_date    DATE            NOT NULL,
    payment_method  VARCHAR(20)     NOT NULL,
    bank_account_id UUID            NOT NULL REFERENCES bank_accounts(id),
    notes           TEXT            NULL,
    source          VARCHAR(20)     NOT NULL DEFAULT 'MANUAL',
    ai_categorised  BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ     NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_amount_positive     CHECK (amount > 0),
    CONSTRAINT chk_date_not_future     CHECK (expense_date <= CURRENT_DATE),
    CONSTRAINT chk_payment_method      CHECK (payment_method IN ('CASH','CREDIT_CARD','DEBIT_CARD','BANK_TRANSFER','OTHER')),
    CONSTRAINT chk_source              CHECK (source IN ('MANUAL','BANK_IMPORT')),

    PRIMARY KEY (id, expense_date)
) PARTITION BY RANGE (expense_date);

-- ── Year partitions ───────────────────────────────────────────────────────────
-- Covers 5 years of active data. The worker creates new partitions each December
-- and archives the oldest each January.
CREATE TABLE expenses_2023 PARTITION OF expenses
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');

CREATE TABLE expenses_2024 PARTITION OF expenses
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

CREATE TABLE expenses_2025 PARTITION OF expenses
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');

CREATE TABLE expenses_2026 PARTITION OF expenses
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE TABLE expenses_2027 PARTITION OF expenses
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

CREATE TABLE expenses_2028 PARTITION OF expenses
    FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');

CREATE TRIGGER trg_expenses_set_updated_at
    BEFORE UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Applies to all partitions automatically (PostgreSQL 13+).
CREATE TRIGGER trg_expenses_lock_created_at
    BEFORE UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
