-- Splits expense_setup from expense_app so a SQL injection through the app
-- role has no path to escalate via SET LOCAL ROLE expense_setup.
--
-- v2.0 originally also turned expense_setup into a LOGIN role here (with a
-- Flyway-placeholder password) so the setup Hikari pool could connect as it.
-- That ALTER is removed under the Option-A pivot (see V17 header / ADR-0011):
-- on managed Postgres the master user cannot grant BYPASSRLS, so the setup
-- pool now connects as the table-owner role instead. expense_setup remains
-- NOLOGIN; later migrations' GRANTs to it are vestigial.

REVOKE expense_setup FROM expense_app;

-- login() records failed attempts and reads the sliding-window count.
-- user_login_failures was added in v1.1; V17 predates it, so the grant
-- lives here. Vestigial under the Option-A pivot (setup pool connects as
-- the table owner) but kept to preserve the per-table audit trail of what
-- the setup escape hatch is *intended* to touch.
GRANT SELECT, INSERT ON user_login_failures TO expense_setup;
