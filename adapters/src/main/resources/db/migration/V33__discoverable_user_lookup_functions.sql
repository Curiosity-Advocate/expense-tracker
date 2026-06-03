-- Delegation (D1) needs two cross-user lookups that the users RLS policy
-- (own-row-only) otherwise blocks:
--
--   1. Resolve a granteeUsername to a user id when creating an access grant —
--      but ONLY if that user opted into delegation (is_discoverable = TRUE).
--   2. Resolve the counterparty's username when listing a user's grants (the
--      other party on each grant is, by definition, not the caller).
--
-- Without these, AccessGrantService.create() always threw
-- GranteeNotDiscoverableException and listForUser() returned null counterparty
-- usernames, because findByUsernameAndIsDiscoverableTrue / findAllById run on
-- the app pool under the caller's RLS context and cannot see other users.
--
-- These SECURITY DEFINER functions run as the function owner (the migration/
-- table-owner role), which bypasses the users RLS policy (users is not FORCE'd).
-- They expose ONLY the id / username — never password_hash, email, lock state —
-- so they are safe to grant to the application role. STABLE + a pinned
-- search_path follow the SECURITY DEFINER hardening convention.

-- (1) Discoverable-user lookup: returns 0 or 1 rows.
CREATE OR REPLACE FUNCTION find_discoverable_user(p_username text)
RETURNS TABLE(user_id uuid)
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
    SELECT id FROM users
    WHERE username = p_username
      AND is_discoverable = TRUE
$$;

-- (2) Username resolution by id: returns 0 or 1 rows. Used to label the
-- counterparty on a grant; leaks only the username.
CREATE OR REPLACE FUNCTION username_of(p_id uuid)
RETURNS TABLE(username varchar)
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public
AS $$
    SELECT username FROM users WHERE id = p_id
$$;

-- The app role calls both; EXECUTE on a SECURITY DEFINER function runs with the
-- definer's privileges, not the caller's.
GRANT EXECUTE ON FUNCTION find_discoverable_user(text) TO expense_app;
GRANT EXECUTE ON FUNCTION username_of(uuid)            TO expense_app;
