-- Pre-authentication operations (register, login, default user setup) need to
-- bypass the user_isolation RLS policy because no user context exists yet.
--
-- v2.0 design: a dedicated NOLOGIN role with BYPASSRLS that the setup pool
-- connects as. This works on self-hosted Postgres where the bootstrap user
-- is a true superuser and can grant BYPASSRLS.
--
-- Render (and every other managed Postgres — RDS, Cloud SQL, Supabase, Neon
-- free tier) does NOT give superuser access. The master user has CREATEROLE
-- but not BYPASSRLS, so it cannot create roles with the BYPASSRLS attribute.
-- See ADR-0011 "Setup-pool RLS bypass" section.
--
-- Resolution: the setup pool now connects as the table-owner role (the same
-- role that Flyway runs as, i.e. the DB_SUPERUSER_USERNAME role). Postgres
-- skips RLS automatically for table owners unless FORCE ROW LEVEL SECURITY is set,
-- which we don't use. The expense_setup role is preserved here (NOLOGIN, no
-- BYPASSRLS) so later migrations' GRANTs to it still resolve — but nothing
-- connects as it at runtime.

CREATE ROLE expense_setup NOLOGIN;

-- Tight permissions: only what register, login, and setupNewUser actually need.
-- Vestigial as of the Option-A pivot — kept so V17 still records the security
-- intent and so later migrations' GRANTs to expense_setup remain valid SQL.
GRANT SELECT, INSERT, UPDATE ON users TO expense_setup;
GRANT INSERT ON bank_accounts TO expense_setup;

-- Membership grant kept for parity with v1.0; V20 revokes it.
GRANT expense_setup TO expense_app;
