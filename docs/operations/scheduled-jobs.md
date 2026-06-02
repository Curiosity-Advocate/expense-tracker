# Scheduled Jobs

> **Context:** Expands [overview.md §7](../overview.md#7-how-the-system-stays-healthy). See also [deployment.md](deployment.md) for how the Worker is hosted. Driven by: F34, F35, F36, F37, N19, N20, N21, N22.

In v1.0 the Worker process owns one mechanism: a cron scheduler for housekeeping and partition management. The job-queue consumer is designed for v2.0 (B3, normalisation worker) — no `jobs` table or consumer loop exists in v1.0. This document covers what runs, when, and why.

---

## The schedule

All jobs run in UTC. Staggered to avoid resource collision and to ensure cleanup runs before view refresh.

| Time (UTC) | Job | What it does |
|---|---|---|
| Daily 02:05 | `deleteExpiredIdempotencyKeys` | DELETE FROM `expense_idempotency_keys` WHERE `expires_at < NOW()` |
| Daily 02:10 | `deleteOldLoginFailures` | DELETE FROM `user_login_failures` WHERE `attempted_at < NOW() - 30 days` |
| Daily 02:15 | `cleanupJobExecutionState` | Prune `job_execution_state`: SUCCESS > 1 day, ALERTED > 7 days |
| Daily 02:20 | `deleteExpiredRefreshTokens` | DELETE FROM `refresh_tokens` WHERE `expires_at < NOW()` |
| Daily 02:30 | `refresh` (materialised views) | `REFRESH MATERIALIZED VIEW CONCURRENTLY` for both summary views |
| Dec 1 01:00 | `createNextYearPartition` | `CREATE TABLE IF NOT EXISTS expenses_<Y+1> PARTITION OF expenses ...` |
| Jan 1 02:00 | `archiveOldPartitions` | `ALTER TABLE expenses DETACH PARTITION IF EXISTS expenses_<Y-5>` |

Implemented in:

- `worker/src/main/java/com/finance/job/CleanupJob.java` — all four daily cleanup methods
- `worker/src/main/java/com/finance/job/MaterializedViewRefreshJob.java` — view refresh
- `worker/src/main/java/com/finance/job/PartitionMaintenanceJob.java` — annual partition create/detach

### Cron expression syntax

Spring's six-field cron format:

```
"0 0 2 * * *"
 ┬ ┬ ┬ ┬ ┬ ┬
 │ │ │ │ │ └── day of week
 │ │ │ │ └──── month
 │ │ │ └────── day of month
 │ │ └──────── hour
 │ └────────── minute
 └──────────── second
```

`"0 0 2 * * *"` = every day at 02:00:00.

---

## Why these jobs are needed

**Expired refresh tokens cleanup (F36, S4).** Every login + every refresh writes a row to `refresh_tokens`. The table is single-source-of-truth for rotation chains. Rows where `expires_at < NOW()` are past the 7-day max-session cap and cannot be rotated further; the DELETE catches both naturally-expired rows and rotated/logged-out rows (revocation doesn't shorten `expires_at`, so revoked rows age out alongside expired ones). Cleanup keeps the table bounded. Superseded `deleteExpiredRevokedTokens` (v1.0) — see [ADR-0009](../decisions/0009-jwt-revocation-via-jti-table.md).

**Expired idempotency keys cleanup (F36).** Same pattern. The keys have a 24-hour TTL; past that they cannot do anything useful, but they take space. Cleanup keeps lookups fast.

**Materialised view refresh (F37).** Summary endpoints read from materialised views (N18). Without refresh, today's expenses do not appear in today's summary. In v1.0 the refresh is fired by the worker on a schedule. The AOP-driven on-write refresh is the designed pattern for low-write volume — see [ADR-0008](../decisions/0008-aop-materialised-view-refresh.md) for the evolution path.

---

## Idempotency by fixed condition (N22)

All jobs are idempotent by design. The mechanism is to use a fixed condition that naturally includes anything previously targeted.

Concrete example — `deleteExpiredRefreshTokens`:

```sql
DELETE FROM refresh_tokens WHERE expires_at < NOW();
```

The condition `expires_at < NOW()` is independent of run history. If yesterday's run successfully deleted some rows, today's run includes them in its conceptual target set — but they are already gone, so the DELETE is a no-op for them. The condition never changes between runs; re-running either does the work or has no effect.

This means **failure handling is trivial — re-run the job**. No compensation logic, no transactional outbox, no exactly-once delivery machinery.

Same shape applies to:

- **Partition creation** — "create `expenses_<year>` if it does not already exist".
- **Partition archival** — "mark partitions where `partition_year < current_year - 5` as ARCHIVED if they are not already".
- **Idempotency key cleanup** — same DELETE shape.

The condition is the contract. Implementations evolve, the condition stays stable.

---

## Failure handling (N21)

Every job is wrapped in `JobFailureAlerter.executeMonitored(jobName, runnable)`, which provides three guarantees:

**1. In-tick retry with exponential backoff.** Up to five attempts per scheduled tick — backoffs of 30s → 60s → 120s → 240s between attempts (~7.5 minutes of total backoff). One transient failure (Postgres restart, network blip) usually heals before the loop gives up.

**2. Persistent state in `job_execution_state`.** Each job has one row keyed by `job_name`, tracking `status` (RUNNING / SUCCESS / ALERTED), `attempt_count`, `last_error`, `created_at`, `updated_at`. The row is updated in a `REQUIRES_NEW` transaction so the write commits even if the job's own transaction rolls back. This means counters survive a worker restart.

**3. Crash recovery on the next tick.** If a tick crashes mid-retry, the row is left with `status = RUNNING` and a stale `updated_at`. The next scheduled invocation detects this (`updated_at < NOW() - 15 minutes`) and resumes from `savedAttempt + 1` rather than restarting at attempt 1. If the saved attempt already exhausted the budget, the alert fires immediately on resume.

**4. Email alert when all attempts fail.** Final `status = ALERTED`, an email goes to `${alerts.job-failure.recipient}` (configured per environment), and the exception is rethrown so Spring's scheduler logs it. Local dev points SMTP at MailHog (port 1025) so alerts never reach a real inbox.

Configuration in `worker/src/main/resources/application.yml`:

```yaml
alerts:
  job-failure:
    recipient: ${JOB_FAILURE_ALERT_RECIPIENT:}
    from: ${JOB_FAILURE_ALERT_FROM:noreply@expense-tracker.local}
    max-attempts: 5
    initial-backoff-ms: 30000
```

> If `recipient` is left blank the alerter no-ops with a WARN log instead of crashing, so an unconfigured deploy is still safe.

Structured JSON logs continue to capture every attempt (the alerter logs `attempt 1/5 failed`, `attempt 2/5 failed`, ... `failed all 5 attempts`), so the email is a notification on top of the log trail, not a replacement for it.

```json
{"ts":"2026-05-19T02:20:00.123+0000","level":"INFO","logger":"c.f.job.CleanupJob","msg":"Cleaned up 42 expired refresh token(s)"}
```

---

## Why the Worker uses superuser DB credentials

RLS policies on user-scoped tables filter by `current_setting('app.current_user_id')`. The cleanup jobs delete rows **across all users** — no single user's ID would satisfy the policy. The Worker therefore connects with **superuser** credentials that bypass RLS entirely.

This is exactly the trade-off described in [ADR-0011](../decisions/0011-three-layer-rls-defence.md): the application role respects RLS, the Worker role bypasses it. Two roles, two purposes. The application is never able to bypass RLS through the API path.

In `worker/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    username: ${DB_SUPERUSER_USERNAME}
    password: ${DB_SUPERUSER_PASSWORD}
  flyway:
    enabled: false   # API module owns migrations — see deployment.md
```

`flyway.enabled: false` is critical — if both API and Worker tried to run migrations at startup, they would race on the `flyway_schema_history` table and corrupt it.

---

## What does *not* run as a cron job

- **JWT signature validation** — happens on every request in the auth filter, not a job.
- **Access-grant expiry enforcement** — happens at the gateway filter at request time. The daily cleanup is for data hygiene only; security does not depend on it running.
- **Backup** — handled by Render's managed PostgreSQL (in production).

---

## What does *not* run locally

Cron jobs are inside the Spring process. If the Worker is not running, no cron fires.

This is harmless for development:

- Token / idempotency cleanup not running → expired rows accumulate; the auth filter still rejects them at request time
- Materialised view refresh not running → summary queries return stale numbers

To exercise the jobs locally, start the Worker (`./gradlew :worker:bootRun`) or invoke the underlying SQL directly via psql.
