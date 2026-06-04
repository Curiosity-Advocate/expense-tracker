-- Fix the v2.0 RLS policies: they were declared AS RESTRICTIVE with no
-- accompanying PERMISSIVE policy, which in PostgreSQL means the table is
-- default-deny — restrictive policies only SUBTRACT from what permissive
-- policies grant, and with zero permissive policies nothing is visible or
-- insertable. The app role (expense_app) therefore could not SELECT or INSERT
-- any of these tables at all, breaking delegation (access_grants / sudo_tokens)
-- and CSV import (csv_imports / csv_import_connections / raw_bank_transactions)
-- whenever they ran through the app pool. (It never surfaced because those
-- features were never exercised end-to-end and the integration tests that
-- exercise them weren't running — see V32 / the test-suite fixes.)
--
-- The V13 tables (users, expenses, …) were correctly PERMISSIVE; the v2.0
-- migrations used RESTRICTIVE by mistake. Each table has a single user_isolation
-- policy whose USING clause already expresses the per-user isolation, so the
-- correct form is PERMISSIVE (the default). The setup pool still bypasses RLS via
-- table ownership; the app role now gets exactly its own rows.
--
-- A policy's PERMISSIVE/RESTRICTIVE nature can't be ALTERed, so each is dropped
-- and recreated. The USING expression keeps the V32 fail-closed form
-- (NULLIF(current_setting(...), '')) and is repeated as WITH CHECK so INSERT is
-- governed by the same predicate.

-- access_grants — visible/insertable to either party.
DROP POLICY user_isolation ON access_grants;
CREATE POLICY user_isolation ON access_grants
    USING (grantor_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid
        OR grantee_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid)
    WITH CHECK (grantor_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid
        OR grantee_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

-- sudo_tokens — scoped to the grantee who minted them.
DROP POLICY user_isolation ON sudo_tokens;
CREATE POLICY user_isolation ON sudo_tokens
    USING (grantee_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid)
    WITH CHECK (grantee_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

-- refresh_tokens — standard user_id scope (app pool only reads via setup pool
-- today, but keep it consistent and correct).
DROP POLICY user_isolation ON refresh_tokens;
CREATE POLICY user_isolation ON refresh_tokens
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

-- raw_bank_transactions — standard user_id scope.
DROP POLICY user_isolation ON raw_bank_transactions;
CREATE POLICY user_isolation ON raw_bank_transactions
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

-- dead_letters — standard user_id scope.
DROP POLICY user_isolation ON dead_letters;
CREATE POLICY user_isolation ON dead_letters
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

-- csv_import_connections — standard user_id scope.
DROP POLICY user_isolation ON csv_import_connections;
CREATE POLICY user_isolation ON csv_import_connections
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);

-- csv_imports — standard user_id scope.
DROP POLICY user_isolation ON csv_imports;
CREATE POLICY user_isolation ON csv_imports
    USING (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', TRUE), '')::uuid);
