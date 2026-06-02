-- Ensure the database timezone is always UTC, never the host OS default.
-- The CHECK (expense_date <= CURRENT_DATE) constraint in V5 depends on this:
-- without an explicit setting, CURRENT_DATE returns dates in whatever timezone
-- the OS happens to be configured for, which causes inconsistent validation
-- across local Docker, Render, and any future deployment target.
--
-- API callers are required to send expense_date as the date in UTC.
-- Hikari's connection-init-sql in application.yml re-asserts the same setting
-- per connection as a second layer of defence.
ALTER DATABASE current_database() SET timezone TO 'UTC';
