-- Partitioned by expense_date year.
-- PostgreSQL requires the partition key to be part of the primary key —
-- this is a hard constraint, not a style choice.
-- Consequence: expense_date travels everywhere expense_id does.
CREATE TABLE expenses (
    id                      UUID            NOT NULL,
    user_id                 UUID            NOT NULL REFERENCES users(id),
    amount                  NUMERIC(12,2)   NOT NULL,
    merchant_name           VARCHAR(255)    NOT NULL,
    expense_date            DATE            NOT NULL,
    notes                   TEXT            NULL,
    payment_method          VARCHAR(50)     NULL,
    bank_account_id         UUID            NOT NULL,
    source                  VARCHAR(20)     NOT NULL,
    bank_status             VARCHAR(20)     NULL,
    external_transaction_id VARCHAR(255)    NULL,
    idempotency_key         UUID            NULL,
    ai_categorised          BOOLEAN         NOT NULL DEFAULT FALSE,
    is_merged               BOOLEAN         NOT NULL DEFAULT FALSE,
    merged_from             UUID[]          NULL,
    deleted_at              TIMESTAMPTZ     NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    PRIMARY KEY (id, expense_date),

    CONSTRAINT chk_source
        CHECK (source IN ('MANUAL', 'BANK_IMPORT')),
    CONSTRAINT chk_bank_status
        CHECK (bank_status IN ('PENDING', 'POSTED') OR bank_status IS NULL),
    CONSTRAINT chk_amount_positive
        CHECK (amount > 0),
    CONSTRAINT uq_external_transaction
        UNIQUE (user_id, external_transaction_id)
) PARTITION BY RANGE (expense_date);

-- Active year partitions
CREATE TABLE expenses_2021
    PARTITION OF expenses
    FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');

CREATE TABLE expenses_2022
    PARTITION OF expenses
    FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');

CREATE TABLE expenses_2023
    PARTITION OF expenses
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');

CREATE TABLE expenses_2024
    PARTITION OF expenses
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

CREATE TABLE expenses_2025
    PARTITION OF expenses
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');

CREATE TABLE expenses_2026
    PARTITION OF expenses
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

-- Trigger: merged_from and is_merged are immutable once set.
-- The audit trail of a merge can never be erased.
CREATE OR REPLACE FUNCTION enforce_merged_from_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.merged_from IS NOT NULL
        AND NEW.merged_from IS DISTINCT FROM OLD.merged_from THEN
        RAISE EXCEPTION 'merged_from is immutable once set';
    END IF;
    IF OLD.is_merged = TRUE AND NEW.is_merged = FALSE THEN
        RAISE EXCEPTION 'is_merged cannot be unset once true';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_merged_from_immutable
    BEFORE UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION enforce_merged_from_immutable();

CREATE TRIGGER trg_expenses_set_updated_at
    BEFORE UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Core query indexes — all partial (active records only)
CREATE INDEX idx_expenses_user_date
    ON expenses(user_id, expense_date DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_expenses_user_merchant
    ON expenses(user_id, merchant_name)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_expenses_user_account
    ON expenses(user_id, bank_account_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_expenses_external_id
    ON expenses(user_id, external_transaction_id)
    WHERE external_transaction_id IS NOT NULL;