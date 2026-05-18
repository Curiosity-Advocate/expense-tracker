-- Bank accounts associated with a user.
-- Every user gets CASH and CRYPTO system accounts automatically at registration
-- (created by DefaultUserSetupService). These are is_system = TRUE and cannot
-- be deleted or renamed.
--
-- Real bank accounts (account_type = 'BANK') are v2.0 via Basiq integration.
-- The table and enum values are defined now so the schema doesn't need changing later.
CREATE TABLE bank_accounts (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id),
    name            VARCHAR(50)     NOT NULL,
    account_type    VARCHAR(10)     NOT NULL,
    is_system       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_account_type CHECK (account_type IN ('CASH', 'CRYPTO', 'BANK')),
    CONSTRAINT uq_user_account_name UNIQUE (user_id, name)
);

CREATE TRIGGER trg_bank_accounts_set_updated_at
    BEFORE UPDATE ON bank_accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_bank_accounts_lock_created_at
    BEFORE UPDATE ON bank_accounts
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
