# Local Development

> **Context:** Expands [overview.md §7](../overview.md#7-how-the-system-stays-healthy). See also [deployment.md](deployment.md) for production deployment and [scheduled-jobs.md](scheduled-jobs.md) for the worker schedule.

Everything needed to run the system on your own machine. The API and Worker run directly via Gradle; only PostgreSQL runs in Docker.

---

## Prerequisites

- Java 21 (Temurin recommended)
- Docker + Docker Compose
- A shell (zsh, bash, fish — any POSIX shell)

Gradle is supplied via the `gradlew` wrapper — no system Gradle installation is required.

---

## Environment variables

Copy `.env.example` to `.env` and fill in real values. `.env` is gitignored — never commit real secrets.

```
# PostgreSQL
DB_SUPERUSER_USERNAME=postgres
DB_SUPERUSER_PASSWORD=change_me_superuser

DB_USERNAME=expense_app           # application role — lower privilege
DB_APP_PASSWORD=change_me_app

DB_URL=jdbc:postgresql://localhost:5432/expense_db

# JWT
JWT_SECRET=change_me_at_least_32_chars_long_xxxxxxxxxx
# Generate with:  openssl rand -hex 32
```

**Why two DB roles.** The superuser runs Flyway migrations (DDL — create tables, triggers, indexes). The application role runs at runtime with only DML privileges. The split is the same one used in production. See [ADR-0011](../decisions/0011-three-layer-rls-defence.md) for context.

---

## Starting PostgreSQL

```bash
docker compose up -d
```

This brings up `postgres:16-alpine` with:
- Database `expense_db` created
- The superuser created from `DB_SUPERUSER_USERNAME` / `DB_SUPERUSER_PASSWORD`
- `docker/init-db.sh` runs once on first start, creating the application role from `DB_USERNAME` / `DB_APP_PASSWORD`
- Volume `postgres_data` persists data across container restarts
- Health check via `pg_isready` — Docker reports the container healthy once Postgres is accepting connections

To wipe local data and start over:

```bash
docker compose down -v   # -v removes the volume
docker compose up -d     # rebuild and run init-db.sh again
```

---

## Running the API

```bash
./gradlew :api:bootRun
```

On first start:

1. Spring loads `application.yml` and reads env vars
2. Flyway connects as superuser and runs V1..VN migrations against the empty DB
3. Hibernate validates entity classes against the schema
4. JwtAuthenticationFilter, RlsSessionAspect, SecurityConfig register
5. App listens on `http://localhost:8080`

Swagger UI is at `http://localhost:8080/swagger-ui.html`. Use it as the live demo layer — no frontend needed for v1.0.

---

## Running the Worker (optional locally)

```bash
./gradlew :worker:bootRun
```

The Worker has no HTTP server. It starts up, registers `@Scheduled` methods, and sits idle until the next cron firing. Locally you typically only run it when you specifically want to test scheduled jobs.

When the Worker is **not** running:
- Expired tokens and idempotency keys accumulate in the DB — they are still rejected at request time, so functionality is unaffected
- Materialised views are not refreshed nightly, so summary queries may show stale data

Both are harmless for development. See [scheduled-jobs.md](scheduled-jobs.md) for the full schedule.

---

## Running the tests

```bash
./gradlew test          # all modules
./gradlew :api:test     # API only
./gradlew :core:test    # core (pure Java, no Spring context)
```

> **Note — there are no tests yet.** The Spring Boot starter-test dependency is declared in every module's `build.gradle`, so the `test` task exists and runs without error, but `src/test/` is empty across the project. Establishing the unit-testing strategy and writing the foundational tests is step 3 of the in-flight work plan (see [../roadmap.md](../roadmap.md)).

---

## Common tasks

**Reset the DB without rebuilding the container:**
```bash
docker compose exec db psql -U postgres -d expense_db -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
./gradlew :api:bootRun   # Flyway re-runs all migrations
```

**Connect to the DB:**
```bash
docker compose exec db psql -U postgres -d expense_db
# or via the app role:
docker compose exec db psql -U expense_app -d expense_db
```

**Refresh materialised views manually:**
```sql
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_expense_summary;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_merchant_summary;
```

**Force-expire revoked tokens for cleanup-job testing:**
```sql
UPDATE revoked_tokens SET expires_at = NOW() - INTERVAL '1 day';
```

---

## Troubleshooting

**`port 5432 already in use`.** A previous Postgres is still running. Either stop the host Postgres or change the port mapping in `docker-compose.yml`.

**`relation "..." does not exist` on app start.** Flyway has not run successfully. Check `./gradlew :api:bootRun` startup logs for migration failures. The most common cause is that the migration history table got corrupted — see the reset commands above.

**`current_setting('app.current_user_id')` returns NULL in psql.** This is expected — RLS policies return zero rows when the session variable is unset. To query as a specific user from psql:
```sql
SET app.current_user_id = '<uuid>';
SELECT * FROM expenses;   -- returns rows for that user
```
