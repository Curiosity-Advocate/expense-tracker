-- Mirrors docker/init-db.sh for the Testcontainers Postgres.
-- Creates the expense_app role with grants on the public schema so Spring
-- can connect with it once Flyway has run. expense_setup is created later
-- by V17 and given LOGIN + password by V20.
-- Keep this file in sync with docker/init-db.sh when role permissions change.

CREATE ROLE expense_app LOGIN PASSWORD 'test_app_password';

GRANT CONNECT ON DATABASE test TO expense_app;
GRANT USAGE ON SCHEMA public TO expense_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO expense_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO expense_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON TABLES TO expense_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON SEQUENCES TO expense_app;
