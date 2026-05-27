-- Row Level Security — third layer of the three-layer data isolation model:
--   Layer 1: application always passes userId in service method signatures
--   Layer 2: repository WHERE clauses filter by userId
--   Layer 3: these policies — PostgreSQL rejects any query that would return
--             another user's rows regardless of what the application code sent.
--
-- A bug in layers 1 or 2 produces an empty result set, never a data leak.
--
-- app.current_user_id is set by RlsSessionAspect via SET LOCAL before every
-- @Transactional method. SET LOCAL scopes it to the current transaction only —
-- cleared automatically when the transaction ends (HikariCP connection reuse safe).
--
-- current_setting('app.current_user_id', TRUE) — the TRUE parameter returns NULL
-- instead of throwing if the variable is not set. NULL produces no matching rows
-- (fail-closed), which is the correct behaviour for a security control.

ALTER TABLE users                       ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_accounts               ENABLE ROW LEVEL SECURITY;
ALTER TABLE categories                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE expenses                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_categories          ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_idempotency_keys    ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_targets             ENABLE ROW LEVEL SECURITY;
ALTER TABLE target_categories           ENABLE ROW LEVEL SECURITY;

-- users: a user can only see their own row.
CREATE POLICY user_isolation ON users
    USING (id = current_setting('app.current_user_id', TRUE)::uuid);

-- bank_accounts: user sees only their own accounts.
CREATE POLICY user_isolation ON bank_accounts
    USING (user_id = current_setting('app.current_user_id', TRUE)::uuid);

-- categories: user sees system categories (user_id IS NULL) and their own private ones.
CREATE POLICY user_isolation ON categories
    USING (
        user_id IS NULL
        OR user_id = current_setting('app.current_user_id', TRUE)::uuid
    );

-- expenses: standard user_id isolation.
CREATE POLICY user_isolation ON expenses
    USING (user_id = current_setting('app.current_user_id', TRUE)::uuid);

-- expense_categories: user_id is denormalised from the expense for a fast direct check.
CREATE POLICY user_isolation ON expense_categories
    USING (user_id = current_setting('app.current_user_id', TRUE)::uuid);

-- expense_idempotency_keys
CREATE POLICY user_isolation ON expense_idempotency_keys
    USING (user_id = current_setting('app.current_user_id', TRUE)::uuid);

-- expense_targets
CREATE POLICY user_isolation ON expense_targets
    USING (user_id = current_setting('app.current_user_id', TRUE)::uuid);

-- target_categories: user_id denormalised from the target.
CREATE POLICY user_isolation ON target_categories
    USING (user_id = current_setting('app.current_user_id', TRUE)::uuid);
