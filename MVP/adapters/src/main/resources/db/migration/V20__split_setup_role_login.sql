-- Splits expense_setup from expense_app so they connect on separate Hikari pools.
-- v1.0 granted expense_app membership in expense_setup, which meant any SQL
-- injection through the app role could escalate by issuing SET LOCAL ROLE
-- expense_setup. After this migration, expense_app has no path to expense_setup
-- at all; the three pre-auth methods (register, login, setupNewUser) reach
-- expense_setup via a dedicated connection pool instead. See ADR-0011.

REVOKE expense_setup FROM expense_app;

-- Setup role becomes directly loginable. Password is supplied via a Flyway
-- placeholder so the secret stays out of git. The placeholder resolves from
-- Spring config (spring.flyway.placeholders.db_setup_password) which in turn
-- reads ${DB_SETUP_PASSWORD} from the environment.
ALTER ROLE expense_setup LOGIN PASSWORD '${db_setup_password}';

GRANT CONNECT ON DATABASE ${db_name} TO expense_setup;
GRANT USAGE ON SCHEMA public TO expense_setup;

-- login() records failed attempts and reads the sliding-window count.
-- v1.1 added user_login_failures; V17 predates it, so the grant is added here.
GRANT SELECT, INSERT ON user_login_failures TO expense_setup;
