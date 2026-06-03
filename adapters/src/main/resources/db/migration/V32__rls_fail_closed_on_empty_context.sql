-- Make every RLS policy fail CLOSED (zero rows) when the user context is
-- missing OR empty, instead of throwing.
--
-- ADR-0011 states the RESTRICTIVE policies "return zero rows when the session
-- variable is not set — fail-closed". That held only for the *unset* case, and
-- only on the V13 tables that used current_setting(..., TRUE). Two gaps:
--
--   1. The v2.0 tables (V21–V29) used current_setting('app.current_user_id')
--      WITHOUT the missing_ok flag, so an unset GUC raised
--      "unrecognized configuration parameter" rather than returning zero rows.
--
--   2. On a pooled connection, a prior `SET LOCAL app.current_user_id = '...'`
--      leaves the GUC defined-but-reset-to-'' after its transaction ends. Then
--      current_setting(...) returns '' (not NULL), and ''::uuid raises
--      "invalid input syntax for type uuid: ''" — again an error, not fail-closed.
--      (Production never hits this because RlsSessionAspect always sets the GUC
--      before a query; it surfaced in PoolIsolationIntegrationTest, which probes
--      the no-context path deliberately.)
--
-- Fix: every policy now reads the context as
--      NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid
-- TRUE → unset yields NULL; NULLIF(…, '') → empty yields NULL. A NULL context
-- makes `user_id = NULL` evaluate to NULL (not true) for every row, so the
-- query returns zero rows. Authenticated queries (context = a real uuid) are
-- unaffected: NULLIF('<uuid>', '') = '<uuid>'.

-- ── V13 tables (were already missing_ok; add the empty-string guard) ─────────
ALTER POLICY user_isolation ON users
    USING (id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON bank_accounts
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON categories
    USING (
        user_id IS NULL
        OR user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid
    );

ALTER POLICY user_isolation ON expenses
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON expense_categories
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON expense_idempotency_keys
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON expense_targets
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON target_categories
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

-- ── v2.0 RESTRICTIVE tables (were missing the missing_ok flag entirely) ──────
ALTER POLICY user_isolation ON refresh_tokens
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON access_grants
    USING (grantor_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid
        OR grantee_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON sudo_tokens
    USING (grantee_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON raw_bank_transactions
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON dead_letters
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON csv_import_connections
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

ALTER POLICY user_isolation ON csv_imports
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);
