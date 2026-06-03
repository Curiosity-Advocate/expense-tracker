# ADR-0011 — Three-layer RLS defence

> **Context:** Expands [overview.md §6](../overview.md#6-how-users-interact-with-the-system). Driven by: N1, N2.

**Status:** Accepted. Adopted in v1.0.

---

## Context

Strict data isolation between users is non-negotiable (N1, N2). A single bug in application code must not be enough to leak data across users. The codebase has a single developer and no PR process, so any defence that depends on perfect code is inadequate.

The question is not "should we enforce isolation" but "at how many independent layers, and where".

## Decision

Enforce data isolation at **three independent layers**:

**Layer 1 — Application.** Every service method takes `userId` as an explicit parameter. The auth filter populates `UserPrincipal` in the Spring `SecurityContext` on every request. Controllers read it and pass it down.

**Layer 2 — Aspect / Transaction.** `RlsSessionAspect` intercepts every method annotated `@Transactional`. Before the transaction body runs, the aspect issues `SET LOCAL app.current_user_id = '<uuid>'`. `SET LOCAL` scopes the variable to the current transaction only — this solves the HikariCP connection-reuse problem, where a connection returned to the pool would otherwise carry the previous user's session variable into the next caller's transaction. The aspect runs at `@Order(2)` so it fires after the Spring transaction has been opened.

**Layer 3 — Database.** Every user-scoped table has a `RESTRICTIVE` RLS policy of the form `USING (user_id = current_setting('app.current_user_id')::uuid)`. PostgreSQL rewrites every query to add this filter. RESTRICTIVE mode means **if the session variable is not set, the policy returns zero rows** — fail-closed.

## Consequences

**Positive.**
- A bug in any single layer is caught by the others. A repository missing its `WHERE user_id = ?` filter is still filtered by the RLS policy. A missing `@Transactional` (no session var set) returns zero rows rather than another user's data.
- The DB layer is the **only** layer that truly cannot be bypassed by application code. Even raw SQL run through the application's connection pool is filtered.
- The pattern works uniformly — adding a new table is "create the table + the policy + the trigger" with no per-method changes.

**Negative.**
- `SET LOCAL` is issued on every transaction. The cost is negligible (one parameter-less statement) but it exists.
- A query that legitimately needs to bypass RLS (the worker's cross-user cleanup) must use superuser credentials. Two roles must be maintained: the application role (RLS-bound) and the superuser role (RLS-bypassing).
- Debugging an unexpected empty result requires the developer to remember that the session variable might be missing — easy to overlook the first time.

## Setup role escape hatch

Pre-authentication operations — register, login, and default user setup — cannot satisfy the `user_isolation` policy because no `UserPrincipal` exists yet when they run. With a strict policy applied to all commands, registration's INSERT would be rejected (new row's `id != NULL::uuid`), login's SELECT would return no rows, and the username/email existence check would always report "no duplicates" even when duplicates exist.

To allow these specific operations through without weakening the user-facing isolation, the application maintains **two Hikari connection pools** (adopted in v2.0 — see "Evolution" below for the v1.0 design this replaced):

- **`appDataSource`** — the `@Primary` pool. Connects as `expense_app`. RLS-enforced. Used by every authenticated endpoint and by `PostgresAuthService.logout()` (which has a JWT, so a user context exists).
- **`setupDataSource`** — a dedicated pool capped at `maximumPoolSize: 3`. Connects as `expense_setup`, a `LOGIN BYPASSRLS` role with `GRANT`s limited to `users`, `bank_accounts`, and `user_login_failures`. Reached only through `setupJdbcTemplate` (a `NamedParameterJdbcTemplate` bound to this pool).

The three setup methods — `PostgresAuthService.register()`, `PostgresAuthService.login()`, `DefaultUserSetupService.setupNewUser()` — are annotated `@Transactional(DataSourceConfig.SETUP_TX_MANAGER)`. Spring opens their transaction on `setupTransactionManager` (a `DataSourceTransactionManager` wrapping `setupDataSource`). Their SQL runs through `setupJdbcTemplate` and never touches the app pool. The bean wiring lives in `adapters/.../config/DataSourceConfig.java` — it sits in `adapters` (not `api`) so the adapter services that consume it don't have to reach across the module boundary.

`expense_app` no longer holds membership in `expense_setup` (V20 revokes it). A SQL injection through the app pool can therefore not issue `SET LOCAL ROLE expense_setup` to escalate — the role membership the v1.0 design depended on no longer exists. `RlsSessionAspect` reads the `@Transactional` qualifier and returns early when the method is on `setupTransactionManager`, so the aspect never runs on the setup pool's transactions.

The `user_isolation` policy is untouched. No permissive `WITH CHECK (TRUE)` holes. Authenticated operations stay strictly isolated.

The security boundary is regression-tested by `PoolIsolationIntegrationTest` (`api/src/test/java/com/finance/integration/`), which verifies: (a) `expense_app` cannot `SET LOCAL ROLE expense_setup` (Postgres SQLState `42501`), (b) `pg_has_role(current_user, 'expense_setup', 'MEMBER')` returns `false` on the app pool, (c) the setup pool can INSERT without an `app.current_user_id` set, and (d) the app pool returns zero rows without one.

### Evolution

**v1.0 design (superseded).** A single Hikari pool connecting as `expense_app`. `expense_setup` was `NOLOGIN`; the three setup methods called `RoleElevationService.elevateToSetupRole()` to issue `SET LOCAL ROLE expense_setup` at the start of their transaction. This worked but `expense_app` held membership in `expense_setup`, meaning any SQL injection through the app role could escalate by issuing the same statement. The trade-off was acknowledged at the time and scheduled for v2.0.

**v2.0 fix (S1, V20).** Two pools, `RoleElevationService` deleted, membership revoked. The textbook-secure version that the v1.0 ADR pointed to as future work.

**v2.0 deploy pivot — Option A (Render free tier).** The v2.0 design depends on the bootstrap user being able to grant `BYPASSRLS`. PostgreSQL only allows BYPASSRLS to be granted by a role that itself has BYPASSRLS — which on managed providers (Render, AWS RDS, GCP Cloud SQL, Supabase, Neon free tier) means a true superuser. None of them give you superuser. So `CREATE ROLE expense_setup … BYPASSRLS` in V17 hard-fails on every managed Postgres.

Three options were on the table when this surfaced during the first Render deploy:

- **A — Setup pool connects as the table-owner role.** Postgres skips RLS automatically for table owners (unless `FORCE ROW LEVEL SECURITY` is set on the table, which this codebase does not use). The dedicated `expense_setup` role is preserved in the migrations as a NOLOGIN audit artifact — the grants to it document *which tables* the pre-auth flows are supposed to touch — but nothing connects as it at runtime.
- B — Self-host Postgres (Render Docker, Fly.io, a VPS) to recover superuser.
- C — Collapse to a single pool and rely on Layer 1+2+3 alone.

Picked A. Reasoning: MVP/resume project, deployability on Render free tier is the constraint that frames everything else; A is the only option that ships without changing the deployment target; B and C are days of additional work each.

**Security trade-off acknowledged.** The setup pool now holds master-DB credentials rather than the narrowly-scoped expense_setup privileges (which were `SELECT/INSERT/UPDATE` on `users`, `INSERT` on `bank_accounts`, `SELECT/INSERT` on `user_login_failures`, plus the later V21/V29 grants for `refresh_tokens` and `csv_imports`). A SQL-injection bug in `register`, `login`, `setupNewUser`, `refresh`, `logout`, or the CSV startup-recovery query would, under Option A, expose every table — not just the granted subset. The attack *vector* is unchanged (the same set of hand-written queries on `setupJdbcTemplate`); only the blast radius is larger. This is the cost of running on managed Postgres without superuser.

**What stays valid:**
- Layers 1–3 of the core defence are unchanged. RLS still applies to the app pool (which connects as `expense_app`, a non-owner, non-BYPASSRLS role).
- `RlsSessionAspect` still skips setup-pool transactions (owner-bypass already covers them).
- `expense_app` still has no path to escalate; V20's `REVOKE expense_setup FROM expense_app` is still in effect.
- `PoolIsolationIntegrationTest`'s contract — app pool returns zero rows without `app.current_user_id`, setup pool can read/write without it — still holds (the mechanism shifts from BYPASSRLS to owner-bypass, but the observable behavior is identical).

**FORCE ROW LEVEL SECURITY interaction (V31).** A `BYPASSRLS` role skips RLS even on a table marked `FORCE ROW LEVEL SECURITY`; owner-bypass does **not** — `FORCE` exists specifically to subject the table owner to RLS. Seven tables were created with `FORCE` (`access_grants`, `csv_import_connections`, `csv_imports`, `dead_letters`, `raw_bank_transactions`, `refresh_tokens`, `sudo_tokens`). Under Option A the setup pool *is* the owner, so `FORCE` re-blocks it on those tables. This surfaced post-deploy: `register` (writes `users`, not forced) succeeded, but `login` (writes `refresh_tokens`, forced) failed with "new row violates row-level security policy", which Spring reports as `BadSqlGrammarException` ("bad SQL grammar"). The setup pool touches exactly two FORCE tables without a user context — `refresh_tokens` (login/refresh/logout) and `csv_imports` (startup recovery) — so V31 does `ALTER TABLE … NO FORCE ROW LEVEL SECURITY` on just those two. RLS stays **enabled**: `expense_app` is a non-owner and remains fully isolated; `FORCE` only ever governed owner connections, and the sole runtime owner connection is the setup pool (whose bypass is the entire point). The residual cost is that a manual superuser session also bypasses RLS on those two tables — acceptable where `BYPASSRLS` is unavailable. The other five FORCE tables are only reached by the app pool with `app.current_user_id` set, so they keep `FORCE`.

**Wiring gotcha (the setup pool must actually be the setup pool).** `DataSourceConfig` defines two `HikariDataSource` beans with `appDataSource` `@Primary`. The setup-pool consumers must carry `@Qualifier("setupDataSource")`: `DataSourceConfig` lives in the `adapters` module, which does not apply the Spring Boot Gradle plugin and so compiles without `-parameters`. Without the qualifier, Spring cannot match the `@Bean` method parameter by name, falls back to by-type, and injects the `@Primary` app pool into the setup transaction manager and setup `JdbcTemplate` — silently routing all pre-auth SQL through the RLS-enforced `expense_app` role. The qualifier pins them explicitly regardless of compiler flags.

**Reversion path.** If/when the deployment moves to self-hosted Postgres, restore V17 to its v2.0 form (`CREATE ROLE expense_setup NOLOGIN BYPASSRLS`) and V20 to its v2.0 form (`ALTER ROLE expense_setup LOGIN PASSWORD '${db_setup_password}'`), repoint the setup pool's `username`/`password` in `application.yml` to `expense_setup`/`${DB_SETUP_PASSWORD}`, add `DB_SETUP_PASSWORD` back to `render.yaml` (or the new infra's secret store), and optionally re-`FORCE` `refresh_tokens` + `csv_imports` (a BYPASSRLS setup role doesn't need them un-forced). Nothing else changes — the app/aspect/ADR contracts were written to be agnostic to the bypass mechanism.

## Alternatives considered

- **Application-only filtering (Layer 1 alone).** Rejected — one bug leaks data.
- **DB RLS only (Layer 3 alone).** Would work, but Layer 2 is what bridges application context to DB context. Without it, the session variable would be set with `SET` (session-level), and HikariCP's connection reuse would leak the variable across users on the same connection.
- **Tenant-per-schema isolation.** Considered. Stronger isolation but very heavy ergonomics — every connection picks a schema, every migration runs N times, every join across schemas is awkward. Not warranted at the personal-finance scale.
- **Permissive `WITH CHECK (TRUE)` for INSERT on `users` and `bank_accounts`.** Rejected as the way to handle pre-auth operations. Permissive policies create real holes that anyone with the app role can exploit (e.g., anonymous attacker creating bank accounts for arbitrary user IDs). The setup role pattern keeps the original policy strict and isolates the escape hatch behind a NOLOGIN role.
