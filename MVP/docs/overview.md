# Design Narrative

A walkthrough of how the problem turned into the system that exists today. Read this end-to-end and you understand the shape of the codebase. Every section ends with deep links into the detail files where the reasoning gets concrete.

---

## 1. The problem

Managing personal finances across multiple goals — rent vs buy, investment timelines, grocery optimisation, budget tracking — requires answering questions that cut across many different types of data. Today that data lives in separate, disconnected spreadsheets. Each spreadsheet answers one question in isolation, so the same underlying calculations get repeated across five files, and any question spanning two goals requires manual stitching. The result is that useful questions either do not get asked, or take so long to answer that they do not get asked often enough.

**The goal.** Build a single source of truth for financial data that can eventually support three kinds of question: am I on track against my targets, what does my financial future look like if I change something, and what patterns exist in my spending that I have not noticed?

**The users.** A small group of Australian households — myself and close friends and relatives. Each user manages their own data independently. Occasional access grants allow a more technically comfortable user to step in and correct data quality issues for another, with a time limit.

The approach is three stages, each building on the last. First, get clean data in and target tracking working. Second, automate data collection via bank feeds and receipt scanning, and add projection modelling. Third, add a UI and pattern detection on top of the accumulated data. This document describes stage one — the MVP.

---

## 2. How the problem was carved up

Before any code, the problem was decomposed along two axes: what the system does for whom, and how each piece of data should be stored.

**Business categories** — what the system does:

- **Identity and Access** — who you are, what you are allowed to do, login, logout, token management, access grants between users
- **Expense Management** — the core business data: recording, categorising, querying expenses
- **Financial Intelligence** — targets, projections, summaries derived from expense data
- **Integration** — anything that connects to the outside world, bank sync now, receipt scanning later
- **System Operations** — keeping the system healthy: job queues, retries, failure handling, logging

**Data bands** — how each piece of data should be stored:

- **Slow and critical** — small volume, security-sensitive, must never be lost
- **Slow and important** — small volume, business-meaningful, mutable
- **Frequent and recoverable** — the bulk of the data, edits happen, soft deletes
- **Append-only and immutable** — raw external data, audit trails, logs

**How they connect:**

| Business Category | Data Band |
|---|---|
| Identity and Access | Slow and critical |
| Expense Management | Frequent and recoverable |
| Financial Intelligence | Slow and important |
| Integration | Append-only and immutable |
| System Operations | Both — immutable for records, recoverable for state |

This two-axis decomposition is what every later decision is checked against. The full functional and non-functional requirements that flow from these categories live in [requirements/functional.md](requirements/functional.md) and [requirements/non-functional.md](requirements/non-functional.md).

---

## 3. Architectural implications

Three decisions follow directly from how the problem was carved up.

**Three packages, not one.** The business logic, the HTTP layer, and the worker have completely different operational characteristics. The business logic is the durable core — every future extension builds on top of it. The HTTP layer is about usability and will churn whenever the interface evolves. The worker layer is about system behaviour and observability and will be monitored, scaled, and extended independently. Two functions with completely different responsibilities belong in different packages. This is not premature optimisation; it is what makes the system extensible without making it fragile. The detailed dependency rules and the Gradle module layout live in [architecture/module-boundaries.md](architecture/module-boundaries.md). The rationale for the split is captured in [ADR-0001](decisions/0001-three-package-split.md).

**PostgreSQL, not MySQL or MariaDB or SQLite.** Three requirements drove this. First, Row Level Security is non-negotiable — application-level bugs are a real risk for a solo developer with no PR process, and enforcing isolation at the database layer independently of application code is a deliberate defence. Only PostgreSQL provides RLS among the open-source relational options. Second, background workers are core, and the `FOR UPDATE SKIP LOCKED` pattern makes concurrent workers safe without a dedicated message queue — also a PostgreSQL feature. Third, materialised views with `REFRESH ... CONCURRENTLY` are needed so summary queries do not block during refresh. PostgreSQL is the only database that provides all three natively. The full reasoning is in [ADR-0002](decisions/0002-postgres-for-rls-skip-locked-mvs.md).

**A separate worker process.** Cron jobs, retries, and background processing have different lifecycle and resource profiles from request handling. A separate Spring Boot process for the worker means it can be scaled, restarted, and reasoned about independently of the API. The worker has no HTTP server. It communicates with the API exclusively through the shared database. Detailed responsibilities in [architecture/module-boundaries.md](architecture/module-boundaries.md).

---

## 4. The shape of the system

Three containers — API, Worker, PostgreSQL — sit inside a single system boundary. Users speak HTTPS to the API. The API and Worker both speak JDBC to PostgreSQL. There is no HTTP between API and Worker; the database is their integration point. The full C4 hierarchy (Level 1 context, Level 2 containers, Level 3 components for each process) is in [architecture/c4-diagrams.md](architecture/c4-diagrams.md).

Inside the API, requests flow through a security filter that validates the JWT and sets the user principal, into thin controllers that translate HTTP to commands, into core services that orchestrate business logic, into repository adapters that talk to PostgreSQL. Aspects sit outside the main flow and intercept repositories — one injects the RLS session variable into every transaction, another refreshes materialised views after successful writes.

Inside the Worker, a scheduler fires cron jobs that call the same core services as the API. No HTTP server. No security filter. The Worker is headless.

The core package itself has no infrastructure dependencies — no Spring annotations, no JDBC, no HTTP clients. Domain objects are pure data carriers — point-in-time snapshots of DB state, not live references, with implications for any future multi-read service method ([ADR-0015](decisions/0015-stale-domain-object-snapshots.md)). Port interfaces define what the core needs from infrastructure. Adapters in a separate package implement those ports. The compiler enforces the rule: nothing inside core can import anything from outside core. The full dependency rules and Gradle structure are in [architecture/module-boundaries.md](architecture/module-boundaries.md).

---

## 5. The data the system holds

Twelve tables, two materialised views, and one partition registry. The schema is grouped by business category. The full ERD with column definitions, constraints, triggers, and RLS policies is in [architecture/data-model.md](architecture/data-model.md).

A few conventions apply across every table:

- All primary keys are application-generated UUIDs
- All monetary values are `NUMERIC(12,2)` — never floating point
- All timestamps are `TIMESTAMPTZ` stored in UTC
- Soft deletes via `deleted_at` on every user-facing record — physical deletion never happens ([ADR-0003](decisions/0003-soft-delete-only.md))
- Every table has four audit columns: `created_at`, `updated_at`, `created_by`, `modified_by`. `created_at` is immutable after insert, enforced by both Hibernate and a DB trigger; `updated_at` is DB-owned and fires on every update

Three schema decisions are non-obvious and have their own ADRs:

- The `expenses` table is partitioned by year and uses a composite primary key `(id, expense_date)`. This is required by PostgreSQL when partitioning on `expense_date`, and it propagates to every table that references an expense — `expense_id` never travels without `expense_date`. The rationale is in [ADR-0004](decisions/0004-composite-pk-partitioned-expenses.md). Yearly partitioning and the partition registry are in [ADR-0013](decisions/0013-yearly-expense-partitions.md).
- An expense's category weights are computed at write time and stored on `expense_categories.weight_amount`. The client sends only category names. The server splits evenly. Storing the pre-computed weight eliminates recomputation on every aggregation. The full reasoning, including why client-supplied weights are rejected, is in [ADR-0005](decisions/0005-server-computed-category-weights.md).
- System categories are distinguished from user categories by `user_id IS NULL` rather than a separate `is_system` column. The RLS policy `WHERE user_id IS NULL OR user_id = current_user_id` does the right thing automatically. Details in [ADR-0012](decisions/0012-system-categories-via-null-user-id.md).

The two materialised views (`mv_monthly_expense_summary` and `mv_merchant_summary`) sit behind regular wrapper views that apply the RLS session-variable filter, because PostgreSQL does not run RLS policies on materialised views directly. The wrapper view pattern is in [ADR-0014](decisions/0014-materialised-view-wrapper-for-rls.md). Both materialised views are refreshed `CONCURRENTLY`, which requires a unique index — a direct consequence of the AOP-driven refresh design described in [ADR-0008](decisions/0008-aop-materialised-view-refresh.md).

---

## 6. How users interact with the system

Every interaction is a REST call under `/api/v1`. The complete set of endpoints, request/response shapes, validation rules, and sequence diagrams is in [architecture/api-contract.md](architecture/api-contract.md).

The interaction model has a small number of consistent rules:

- **Authentication is JWT.** Login returns a Bearer token. Every authenticated endpoint requires it. Logout writes the token's `jti` to a revoked-tokens table; the security filter checks that table on every request. A token cannot be reused after logout even if it has not expired. The mechanism is in [ADR-0009](decisions/0009-jwt-revocation-via-jti-table.md).
- **Authorisation is row-level.** The security filter sets the user principal once per request, and an aspect sets the PostgreSQL session variable `app.current_user_id` once per transaction. RLS policies on every user-scoped table do the actual filtering. The three-layer defence — service signatures pass `userId`, repositories filter by `userId`, and PostgreSQL RLS rejects anything that violates isolation — is in [ADR-0011](decisions/0011-three-layer-rls-defence.md).
- **Idempotency on expense create.** The client generates a UUID and sends it as `idempotencyKey`. If a network retry hits the server with the same key inside the 24-hour window, the server returns the original expense rather than creating a duplicate. Details in [ADR-0006](decisions/0006-idempotency-key-on-expense-create.md).
- **Predictions are versioned.** Every prediction response carries `strategyUsed` and `strategyVersion`. New algorithms become new strategy classes — the old ones are never modified. This guarantees that a historical prediction can always be reproduced. The strategy chain and prediction engine design is in [ADR-0007](decisions/0007-strategy-pattern-prediction-engine.md).
- **Summary reads are eventually consistent.** Summary endpoints hit materialised views, not raw expense tables. Every response carries `dataFreshAsOf` so the client can show how stale the data is rather than hiding it.

---

## 7. How the system stays healthy

The worker process exists for housekeeping. Three nightly jobs run on staggered cron schedules — expired revoked-token cleanup, expired idempotency-key cleanup, and materialised-view refresh. Annual jobs (deferred to v2.0 in practice) create the next year's expense partition and archive partitions older than five years. Full schedule, idempotency strategy, and failure handling is in [operations/scheduled-jobs.md](operations/scheduled-jobs.md).

All jobs are idempotent by fixed condition. The expired-token cleanup deletes everything with `expires_at < NOW()` — re-running it tomorrow includes yesterday's expired tokens, which are already gone, so the delete is a no-op. This means failure handling is simple: re-run the job. No rollback or compensation logic.

The worker connects to the same PostgreSQL as the API, but uses superuser credentials. Cleanup deletes span all users, which no user-scoped RLS policy would permit. Only the worker gets superuser; the API uses a restricted role.

Migrations are owned by the API module. The worker has Flyway disabled in its configuration. If both processes ran Flyway at startup they would race on the migration history table.

Deployment uses Docker images built by multi-stage Dockerfiles (build stage with JDK + Gradle, runtime stage with JRE only), GitHub Actions for CI validation, and a `render.yaml` that declares the API service, the worker service, and the managed PostgreSQL instance to Render. The full deployment pipeline is in [operations/deployment.md](operations/deployment.md). For running everything on your own machine, see [operations/local-development.md](operations/local-development.md).

---

## 8. What is not in v1.0 and why

The MVP is deliberately narrow. Several features are designed but deferred:

- **Bank integration** — connecting bank accounts via Basiq, OAuth flow, raw-transaction ingestion with hash chaining, duplicate detection, bank-sync workers. Deferred to v2.0.
- **AI categorisation** — LLM-driven category suggestions for uncategorised transactions, accept/override flow. Deferred to v2.0.
- **Delegation and sudo tokens** — granting temporary access to another user with a step-up auth token, scoped to expense endpoints. Designed in the README but not implemented. Deferred to v2.0.
- **Dead-letter API endpoints** — the table exists as infrastructure but no endpoints to list or retry. Deferred to v2.0 alongside bank sync, which is what would populate the dead letter in the first place.
- **Read replica routing** — the design assumes a future split between primary (writes) and replica (reads). Not implemented in v1.0; single primary handles both.
- **Rate limiting** — designed at the gateway filter; not in v1.0.
- **Active alerting on job failures** — v1.0 captures failures in structured JSON logs. Email alerts on repeated failure planned for v1.1.

The full MVP boundary — what is built today versus what is deferred — and the version-by-version plan are in [roadmap.md](roadmap.md).

---

> **For reviewers and future-me.** Every section above is a doorway. The narrative is intentionally short; the depth is in the detail files. If you find yourself wanting more on any topic, follow the link inline. If a link does not yet exist, that file has not been written and the section in this overview is the source of truth for the moment.
