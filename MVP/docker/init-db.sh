#!/bin/bash
# Creates the application role used by Spring Boot at runtime.
# Flyway runs as the superuser (postgres) for DDL; the app role
# only needs DML access. Separating them limits blast radius if
# the app-level credentials are ever compromised.
set -e

psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<EOF
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$DB_USERNAME') THEN
        CREATE ROLE "$DB_USERNAME" LOGIN PASSWORD '${DB_APP_PASSWORD}';
    END IF;
END;
\$\$;

GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO "$DB_USERNAME";
GRANT USAGE ON SCHEMA public TO "$DB_USERNAME";
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "$DB_USERNAME";
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "$DB_USERNAME";

-- Ensures the app role automatically gets access to any tables
-- created by future Flyway migrations without re-running this script.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON TABLES TO "$DB_USERNAME";
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON SEQUENCES TO "$DB_USERNAME";
EOF
