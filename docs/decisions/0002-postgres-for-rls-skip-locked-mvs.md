# ADR-0002 — PostgreSQL for RLS, SKIP LOCKED, and materialised views

> **Context:** Expands [overview.md §3](../overview.md#3-architectural-implications). Driven by: N1, N2, N17, N18.

**Status:** Accepted. Adopted in v1.0.

---

## Context

Choice of relational database is foundational — changing it after the schema exists is painful and migrations between vendors are non-trivial. Three capabilities are essential for the design:

1. **Row Level Security** (N1, N2). A solo developer with no PR process is one bug away from accidentally leaking data across users. The defence must live at the database layer where application bugs cannot bypass it.
2. **`FOR UPDATE SKIP LOCKED`** for concurrent worker safety. The job queue is a table; multiple workers must be able to poll without locking each other out.
3. **Materialised views with concurrent refresh** (N17, N18). Summary endpoints query pre-aggregated data. The refresh must not block reads, which requires `REFRESH MATERIALIZED VIEW CONCURRENTLY` and a unique index on the view.

## Decision

Adopt **PostgreSQL 16** as the only relational database for the system. All three capabilities are first-class features.

## Consequences

**Positive.**
- RLS policies on every user-scoped table enforce isolation independently of application code. A bug in a repository's `WHERE` clause still returns zero rows because PostgreSQL rewrites every query to add the user filter.
- The worker can run multiple concurrent consumers using `SELECT ... FOR UPDATE SKIP LOCKED` without a dedicated message broker. This defers Redis/RabbitMQ to v2.0 or later.
- Summary queries hit materialised views; refreshes use `CONCURRENTLY` and do not block reads.

**Negative.**
- The system is bound to PostgreSQL-specific features. Porting to a different RDBMS would require redesigning RLS, the job queue pattern, and the materialised view strategy.
- Hosted PostgreSQL on Render's free tier is deleted after 90 days — operational footnote, not a deal-breaker for a personal project, but worth knowing.

## Alternatives considered

- **MySQL / MariaDB.** Has no RLS, no `SKIP LOCKED` (MariaDB), and no concurrent-refresh materialised views. Would require RLS in application code (defeating the defence-in-depth goal), a separate message broker for workers, and read-through caching for summaries.
- **SQLite.** Embedded only, not designed for concurrent server-side access. No RLS. Eliminated early.
- **MongoDB or other NoSQL.** The data is highly relational (categories, expenses, weights, targets, materialised aggregates). Forcing it into documents would create redundant joins-in-application-code. Rejected.
