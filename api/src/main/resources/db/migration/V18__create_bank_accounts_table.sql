-- Bank accounts table — real accounts connected via Basiq and
-- system-reserved accounts (CASH, CRYPTO) created at registration.
--
-- system_account_type is NULL for real bank accounts.
-- For system accounts it holds CASH or CRYPTO.
--
-- bitwarden_secret_id stores only the reference key used to retrieve
-- the OAuth refresh token from Bitwarden at runtime.
-- The token value never enters this database.
CREATE TABLE bank_accounts (
    id                      UUID            PRIMARY KEY,
    user_id                 UUID            NOT NULL REFERENCES users(id),
    institution_name        VARCHAR(100)    NOT NULL,
    account_name            VARCHAR(100)    NOT NULL,
    account_number_masked   VARCHAR(20)     NULL,
    basiq_account_id        VARCHAR(255)    NULL,
    bitwarden_secret_id     VARCHAR(255)    NULL,
    is_system_account       BOOLEAN         NOT NULL DEFAULT FALSE,
    system_account_type     VARCHAR(20)     NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    last_synced_at          TIMESTAMPTZ     NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_bank_account_status
        CHECK (status IN ('ACTIVE', 'DISCONNECTED', 'ERROR')),

    CONSTRAINT chk_system_account_type
        CHECK (system_account_type IN ('CASH', 'CRYPTO') OR system_account_type IS NULL)
);

CREATE TRIGGER trg_bank_accounts_set_updated_at
    BEFORE UPDATE ON bank_accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One CASH account and one CRYPTO account per user maximum.
-- Partial — only enforced for system accounts, real accounts are unrestricted.
CREATE UNIQUE INDEX uq_one_system_account_per_type_per_user
    ON bank_accounts(user_id, system_account_type)
    WHERE is_system_account = TRUE;

CREATE INDEX idx_bank_accounts_user
    ON bank_accounts(user_id)
    WHERE status = 'ACTIVE';