# Roadmap

> **Context:** Expands [overview.md §8](overview.md#8-what-is-not-in-v10-and-why). Backreferences requirements F1–F37 and N1–N22 in [requirements/functional.md](requirements/functional.md) and [requirements/non-functional.md](requirements/non-functional.md).

The MVP is deliberately narrow. This document draws the line between what is built today and what is planned. Each subsequent version is scoped around a single coherent theme.

---

## v1.0 — MVP (current)

**Goal:** prove the core loop — record expenses, set targets, see projections.

### What is built

- **Auth** — register, login, logout, profile view, profile update (discoverability)
- **Categories** — system categories seeded; user-created categories with hierarchy; update
- **Expenses** — create with idempotency, list with filters, single get, patch, soft delete, summary
- **Targets** — create CATEGORY / MULTI_CATEGORY / TOTAL with inclusive/exclusive scope; list; status with naive daily-rate prediction; soft delete
- **Worker** — nightly cleanup of expired revoked tokens, expired idempotency keys, materialised view refresh
- **Infrastructure** — RLS three-layer defence, partitioned `expenses` table, materialised views with concurrent refresh, Flyway migrations, Render deployment

Implements F1–F6, F14–F37, N1–N5, N9–N14, N16–N22.

### What is intentionally not built

| Feature | Why deferred |
|---|---|
| Delegation / sudo tokens (F7–F13, N6 partial) | Designed in [api-contract.md](architecture/api-contract.md) but not implemented. Deferred to v2.0. |
| Bank integration (N6, N7, N8) | Out of scope for "prove the core loop". Designed for v2.0. |
| AI categorisation | Same — depends on bank integration to be useful. |
| Dead-letter API endpoints | The table exists as infrastructure but no endpoints to list or retry. Bank sync is what would populate the dead letter, so they ship together. |
| Read replica routing | Single primary handles both reads and writes at MVP scale. |
| Rate limiting | Not warranted at 10 users. Designed for v2.0. |
| Active alerting on job failures | v1.0 captures failures in structured JSON logs only. Email alerts in v1.1. |
| Active partition creation / archival jobs (F34, F35, N12) | Schema and registry exist. Worker cron jobs are designed but not yet implemented. Slated for v1.1. |

### Known MVP gaps and bugs

- The `@RefreshMaterialisedView` aspect ([ADR-0008](decisions/0008-aop-materialised-view-refresh.md)) is the designed pattern; v1.0 currently relies on the worker's scheduled refresh only. Deferred to v3.0.

The full MVP-vs-built audit happens in the next stage of work (after this documentation pass) and will produce a concrete punch list.

---

## v1.1 — MVP polish ✓ Complete

**Goal:** close the gaps in MVP scope without taking on new features.

All five v1.1 items have shipped:

- **#1 — `adapters/` Gradle module split + ArchUnit test.** JPA entities, repositories, service implementations, and Flyway migrations moved from `api/` to a new `adapters/` module. Domain exceptions and `NaiveDailyRateStrategy` moved to `core/`. The compile-enforced boundary [ADR-0001](decisions/0001-three-package-split.md) promised is now real. ArchUnit rules in `api/src/test/java/com/finance/architecture/ArchitectureTest.java` enforce: no Spring in core packages, `@RestController` only in `com.finance.controller`, `@Entity` only in `com.finance.entity`.
- **#2 — Sliding-window lockout.** `user_login_failures` table + 10-minute window + 15-minute lockout (F4). Replaced the cumulative `users.failed_login_count` counter. Nightly worker purges rows older than 30 days.
- **#3 — Partition lifecycle crons** (F34, F35). December 1 creates next year's partition; January 1 detaches partitions older than five years.
- **#4 — Email alert on cron failure.** `JobFailureAlerter` wraps every scheduled job with 5-attempt retry, exponential backoff, DB-persisted `job_execution_state`, crash recovery from a stale `RUNNING` row, and SMTP alert when all attempts fail. Local dev uses MailHog; production uses Gmail App Password (see [operations/scheduled-jobs.md](operations/scheduled-jobs.md)).
- **#5 — Trace ID propagation.** `TraceIdFilter` reads `X-Trace-Id` (or generates a UUID), puts it on the SLF4J MDC, and echoes it back on the response. Every log line and error envelope carries the same trace id.

---

## v2.0 — Automation: bank integration, delegation, and auth hardening

**Goal:** bring real-world data in without manual entry, enable supervised cross-user data correction, and harden authentication.

### Bank integration

Implements F4 (extended), N6, N7, N8.

- **Basiq CDR integration** — manually-triggered bank sync per user; OAuth flow via Bitwarden-stored credentials; raw transactions stored in `raw_bank_transactions` table (append-only, hash-chained for tamper evidence)
- **Merchant mapping table** — `merchant_mappings(user_id, raw_pattern, friendly_name)` for resolving raw bank merchant strings (e.g. `PYP*AMAZON 1234`) to user-friendly names. CRUD via `/api/v1/merchant-mappings`. Used by the normalisation worker to populate `expenses.merchant_name`.
- **Normalisation worker** — translates Basiq payload to `expenses` rows; uses the job queue + `FOR UPDATE SKIP LOCKED`
- **Duplicate detection** — fuzzy matching between manual and bank-imported expenses; weighted score; PROBABLE_PENDING_SETTLEMENT typed for Basiq's PENDING→POSTED pattern
- **Duplicate resolution UI/API** — user merges or keeps both; merge audit trail immutable via DB trigger
- **Bank-imported expenses immutable** — amount/merchant/date/payment locked; only categories and notes editable
- **Dead-letter API** — list dead letters, manual retry endpoint, surfaced via `/api/v1/dead-letters`
- **Rate limiting** — Bucket4j on bank sync (5 per user per 24h)

### Delegation

Implements F7–F13.

- `POST /api/v1/users/me/access-grants` — create grant with `granteeUsername`, `accessLevel`, `expiresInDays`
- `GET /api/v1/users/me/access-grants` — list grants
- `DELETE /api/v1/users/me/access-grants/{grantId}` — revoke early
- `sudo_tokens` table (SHA-256 hashed) — step-up authentication
- Gateway filter handles `asUserId` query parameter on expense endpoints; scope restricted to expense endpoints only

### Security hardening

- **Split RLS bypass into a dedicated connection pool.** v1.0 grants `expense_app` membership in `expense_setup` so the app role can `SET LOCAL ROLE` for pre-auth operations (register, login, default user setup). A successful SQL injection through `expense_app` could escalate by running the same `SET LOCAL ROLE expense_setup` statement. Move to two separate Hikari pools — one connecting as `expense_app`, one as `expense_setup` — so the app role cannot escalate even if compromised. See [ADR-0011](decisions/0011-three-layer-rls-defence.md).
- **TOTP MFA** — second factor for sensitive operations.
- **Google OAuth login** — alternative to username/password.
- **Short-lived access tokens + refresh tokens** — replaces the 7-day token with 15-minute access + refresh-token rotation (current revocation table moves to refresh tokens only).

---

## v3.0 — Frontend, pattern detection, and intelligence

**Goal:** a real interface, questions the system answers proactively, and intelligent categorisation.

- Mobile or web UI (out of scope for this design; backend remains API-first)
- Pattern detection over accumulated spend history — anomaly flagging, recurring-spend identification, category drift over time
- Mortgage / investment scenario modelling on top of expense history
- Read replica routing for analytics queries (N16 burst handling)
- 99.9% uptime target (three nines) — see [overview.md §1](overview.md#1-the-problem) approach stage 3
- **AI categorisation worker** — LLM-driven category suggestions for uncategorised transactions; written to `ai_suggestions` table; user confirms/overrides via API; before/after structure as optimistic lock; AI acceptance vs override tracked for future model improvement
- **Auto-apply mode** (optional per user) — configurable threshold for accepting AI suggestions without user confirmation
- **Async notifications** — `ApplicationEventPublisher` for domain events; observer-pattern polling endpoint (`/suggestions/pending`); FCM push replaces polling for AI suggestion alerts
- **`@RefreshMaterialisedView` aspect** ([ADR-0008](decisions/0008-aop-materialised-view-refresh.md)) — refresh `mv_*_summary` views after a write commits, falling back to scheduled refresh under load. Today's only refresh is the 02:30 UTC nightly job, so summary endpoints can be up to 12 hours stale.

---

## What never gets built

Some things are deliberately out of permanent scope, not deferred:

- **Multi-currency support.** All amounts are AUD. Multi-currency adds substantial complexity (FX rates, historical re-conversion, base currency choice) for negligible benefit to the user population.
- **Configurable category split weights via API.** The server computing even splits is a deliberate security boundary — see [ADR-0005](decisions/0005-server-computed-category-weights.md). v3.0 AI weighting is a *different* mechanism; the client never controls weights directly.
- **Physical deletion of any record.** See [ADR-0003](decisions/0003-soft-delete-only.md). GDPR/right-to-erasure, if it ever applies, becomes a separate auditable workflow.
