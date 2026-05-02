-- Adds the foreign key constraint from expenses.bank_account_id
-- to bank_accounts.id now that the bank_accounts table exists.
-- Deferred until here because FK references require the target
-- table to exist first.
ALTER TABLE expenses
    ADD CONSTRAINT fk_expenses_bank_account
    FOREIGN KEY (bank_account_id)
    REFERENCES bank_accounts(id);