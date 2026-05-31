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

- **D1 — access_grants table + CRUD API. ✓ Shipped.** V24 creates `access_grants` with dual-clause RLS (grantor_id OR grantee_id matches current user), DB-enforced no-self-grant CHECK, audit columns via the S5 trigger. Three endpoints under `/api/v1/users/me/access-grants` — POST/GET/DELETE — with grantee discoverability gate, 1–30 day expiry bound, and indistinguishable-error responses to prevent username enumeration. Grants exist as records but **are not yet usable for delegation** pending D2 (sudo tokens) and D3 (gateway filter). See [api-contract.md §Access grants](architecture/api-contract.md).
- **D2 — `sudo_tokens` + step-up auth. ✓ Shipped.** V25 creates `sudo_tokens` (FK to access_grants, denormalised grantee_id for RLS, no audit columns matching the security-primitive pattern). One endpoint at `/api/v1/auth/sudo-tokens` mints a token after password re-entry; the raw value is returned once, SHA-256 hash stored. `SudoTokenService.verify(rawToken, granteeId)` joins access_grants at verify time so revoking a grant immediately invalidates all its sudo tokens — no cascade needed. `RefreshTokenGenerator` renamed to `SecureTokenGenerator` since both S4 and D2 use it. SecurityConfig adds a more-specific authenticated rule for `/sudo-tokens` (still under `/api/v1/auth/**` but requires Bearer). Grants remain inert at runtime pending D3's gateway filter.
- **D3 — Gateway filter for `asUserId`. ✓ Shipped.** `AsUserIdFilter` runs after `JwtAuthenticationFilter`, validates `?asUserId=<grantor>` + `X-Sudo-Token` header against the D1 grant + D2 sudo token, and substitutes the SecurityContext principal. `RlsSessionAspect` then sets `app.current_user_id = grantor` (RLS sees A's data) and `app.acting_user_id = grantee` (S5 audit triggers record B). `UserPrincipal` gained an `actingAs` field. Scope restricted to `/api/v1/expenses` via an allow-list. Full D1→D2→D3→S5 chain is end-to-end tested in `DelegationIntegrationTest` via `TestRestTemplate`. See [ADR-0018](decisions/0018-delegation-grants-sudo-tokens-gateway.md).

### Security hardening

- **S1 — Split RLS bypass into a dedicated connection pool. ✓ Shipped.** Two Hikari pools (`appDataSource` connecting as `expense_app`, `setupDataSource` connecting as `expense_setup`). V20 revokes `expense_app`'s membership in `expense_setup`, severing the SQL-injection escalation path. The three pre-auth methods (`register`, `login`, `setupNewUser`) route through `setupJdbcTemplate`; `RoleElevationService` is deleted. The security boundary is regression-tested by `PoolIsolationIntegrationTest`. See [ADR-0011](decisions/0011-three-layer-rls-defence.md).
- **S4 — Short-lived access tokens + refresh-token rotation. ✓ Shipped.** 15-minute access JWTs + 7-day refresh tokens with rotation on every `/refresh`. Reuse detection (presenting a rotated token) cascade-revokes every active chain for the user via `RefreshTokenChainRevoker` in `REQUIRES_NEW`. `session_started_at` is copied unchanged across rotations, capping the chain at the original-login window. V21 creates `refresh_tokens` (single-row-per-issuance, append-only via DB triggers); V22 drops the superseded `revoked_tokens` table. `JwtAuthenticationFilter` is now pure crypto, no DB I/O. See [ADR-0016](decisions/0016-refresh-token-rotation.md).
- **S5 — Row-level audit trail. ✓ Shipped.** `created_by` and `modified_by` UUID columns added to seven user-scoped business tables (V23). Populated by the `set_audit_user` DB trigger, which reads `app.acting_user_id` first and falls back to `app.current_user_id` — forward-compatible with D3's delegation pattern without any S5 changes needed later. `lock_created_by` trigger enforces immutability. Security-infrastructure tables (`refresh_tokens`, `user_login_failures`) intentionally excluded. See [ADR-0017](decisions/0017-row-level-audit-trail.md).

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

### Security enhancements

The v2.0 security work covers what's needed for the threat model — small trusted userbase, RLS-enforced isolation, refresh-token rotation, audit trail. Items below are deferred to v3.0 because the existing auth (BCrypt + lockout + short-lived tokens + RLS + refresh-token rotation) is adequate for ~10 trusted users. They're kept on the roadmap as resume-signal additions if the project ever opens to a wider userbase.

**Auth options (deferred from v2.0):**

- **TOTP MFA** — second factor for sensitive operations. Self-contained (no external dependencies); demonstrates RFC 6238, secret management, recovery-code UX.
- **Google OAuth login** — alternative to username/password. Demonstrates OAuth2 client integration, callback/state handling, external identity linking.

**Refresh-token hardening (deferred from S4):**

- **Device/IP binding on refresh tokens** — bind each refresh token to the IP or device fingerprint that issued it. A refresh request from a new IP triggers full re-authentication. Closes the "attacker rotates indefinitely from a different network" gap that the basic S4 rotation does not catch on its own.
- **Active session UI** — `GET /api/v1/auth/sessions` returns all active refresh tokens for the current user (with last-used time, IP, user agent). `DELETE /api/v1/auth/sessions/{id}` revokes one. Lets users notice and end suspicious sessions from a trusted device without waiting for rotation reuse to fire. Also enables **chain-aware logout** — "log me out of the chain this token came from" — which the current OAuth-style logout deliberately does not do.
- **Anomaly detection on rotation patterns** — rapid back-to-back refreshes, refresh-then-immediate-failure, or geographically improbable usage triggers an alert (or auto-revokes the chain). Closes the gap where an attacker rotates fast enough to never be the one caught by reuse detection.
- **Rate limiting on auth endpoints** — Bucket4j (or equivalent) on `/auth/login`, `/auth/refresh`, and `/auth/logout`. The S4 design defers refresh-spam DoS protection here. B8's rate limiting is scoped only to the bank-sync endpoint; the auth endpoints have their own protection profile (per-IP for login, per-token for refresh).

**Delegation enhancements (deferred from D3):**

- **Log enrichment for delegated requests** — when D3 substitutes the principal (`userId = grantor`, `actingAs = grantee`), structured log lines emitted by downstream services attribute the action to the grantor instead of the actual requestor. Add MDC fields (`delegated_by`, `acting_as`) so log aggregators show "user B acted as A for endpoint X" in one line, without operators having to correlate B's JWT trace with A's audit-column appearances. The TraceIdFilter is the natural place to inject these MDC keys when `principal.isDelegated()`.
- **Stretch — visible delegation context on user-scoped rows.** Currently `expenses.modified_by = B` for a delegation write means "B modified A's expense via a grant"; a reader has to consult both the access_grants table and the audit logs to confirm B did this via delegation rather than (somehow) directly. Add a `modified_via_grant_id UUID NULL REFERENCES access_grants(id)` column to user-scoped business tables. The S5 trigger populates it from a new session variable `app.acting_via_grant_id` set by D3 alongside `acting_user_id`. A non-null value tells a reader: "this row was modified via delegation; here's the grant." Cheap forensics without log mining.

---

## What never gets built

Some things are deliberately out of permanent scope, not deferred:

- **Multi-currency support.** All amounts are AUD. Multi-currency adds substantial complexity (FX rates, historical re-conversion, base currency choice) for negligible benefit to the user population.
- **Configurable category split weights via API.** The server computing even splits is a deliberate security boundary — see [ADR-0005](decisions/0005-server-computed-category-weights.md). v3.0 AI weighting is a *different* mechanism; the client never controls weights directly.
- **Physical deletion of any record.** See [ADR-0003](decisions/0003-soft-delete-only.md). GDPR/right-to-erasure, if it ever applies, becomes a separate auditable workflow.
