-- Pre-authentication operations (register, login, default user setup) need to
-- bypass the user_isolation RLS policy because no user context exists yet.
-- expense_setup is a dedicated NOLOGIN role with BYPASSRLS that the app
-- temporarily activates via SET LOCAL ROLE within those operations' transactions.
-- The role cannot be reached externally — it has no login capability — so the
-- only way to use it is through application code that explicitly elevates.
-- See ADR-0011 for the full architecture.

CREATE ROLE expense_setup NOLOGIN BYPASSRLS;

-- Tight permissions: only what register, login, and setupNewUser actually need.
-- BYPASSRLS lets expense_setup skip RLS policies, but explicit GRANTs still
-- control what operations are allowed at all. Any new pre-auth operation
-- needing additional tables must add its own GRANT in a new migration —
-- this forces explicit security review for every elevated access path.
GRANT SELECT, INSERT, UPDATE ON users TO expense_setup;
GRANT INSERT ON bank_accounts TO expense_setup;

-- Membership: expense_app can SET LOCAL ROLE expense_setup, but cannot use
-- expense_setup outside that explicit elevation.
-- Hardcoded role name — must match init-db.sh's DB_USERNAME env var value
-- (which defaults to 'expense_app'). If you change DB_USERNAME, update this too.
GRANT expense_setup TO expense_app;
