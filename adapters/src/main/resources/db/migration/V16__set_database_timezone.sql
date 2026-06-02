-- Ensure the database timezone is always UTC, never the host OS default.
-- The CHECK (expense_date <= CURRENT_DATE) constraint in V5 depends on this:
-- without an explicit setting, CURRENT_DATE returns dates in whatever timezone
-- the OS happens to be configured for, which causes inconsistent validation
-- across local Docker, Render, and any future deployment target.
--
-- API callers are required to send expense_date as the date in UTC.
-- Hikari's connection-init-sql in application.yml re-asserts the same setting
-- per connection as a second layer of defence.
-- ALTER DATABASE requires a literal name, not a function call (rejected by
-- Postgres 18 — earlier versions parsed it but never honoured it). Wrap in
-- a DO block so the current DB name is resolved at runtime and injected.
DO $$
BEGIN
    EXECUTE 'ALTER DATABASE ' || quote_ident(current_database()) || ' SET timezone TO ''UTC''';
END;
$$;
