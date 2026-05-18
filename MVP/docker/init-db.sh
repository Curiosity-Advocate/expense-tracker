#!/bin/bash
# Creates the application role used by Spring Boot at runtime.
# Flyway runs as the superuser (postgres) for DDL; the app role
# only needs DML access. Separating them limits blast radius if
# the app-level credentials are ever compromised.
set -e

psql -U postgres -d expense_db <<EOF
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'expense_app') THEN
        CREATE ROLE expense_app LOGIN PASSWORD '${APP_DB_PASSWORD}';
    END IF;
END;
\$\$;

GRANT CONNECT ON DATABASE expense_db TO expense_app;
GRANT USAGE ON SCHEMA public TO expense_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO expense_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO expense_app;

-- Ensures the app role automatically gets access to any tables
-- created by future Flyway migrations without re-running this script.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON TABLES TO expense_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON SEQUENCES TO expense_app;
EOF
