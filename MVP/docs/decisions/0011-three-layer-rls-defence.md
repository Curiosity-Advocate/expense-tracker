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

The three setup methods — `PostgresAuthService.register()`, `PostgresAuthService.login()`, `DefaultUserSetupService.setupNewUser()` — are annotated `@Transactional(DataSourceConfig.SETUP_TX_MANAGER)`. Spring opens their transaction on `setupTransactionManager` (a `DataSourceTransactionManager` wrapping `setupDataSource`). Their SQL runs through `setupJdbcTemplate` and never touches the app pool. The bean wiring lives in `api/.../config/DataSourceConfig.java`.

`expense_app` no longer holds membership in `expense_setup` (V20 revokes it). A SQL injection through the app pool can therefore not issue `SET LOCAL ROLE expense_setup` to escalate — the role membership the v1.0 design depended on no longer exists. `RlsSessionAspect` reads the `@Transactional` qualifier and returns early when the method is on `setupTransactionManager`, so the aspect never runs on the setup pool's transactions.

The `user_isolation` policy is untouched. No permissive `WITH CHECK (TRUE)` holes. Authenticated operations stay strictly isolated.

The security boundary is regression-tested by `PoolIsolationIntegrationTest` (`api/src/test/java/com/finance/integration/`), which verifies: (a) `expense_app` cannot `SET LOCAL ROLE expense_setup` (Postgres SQLState `42501`), (b) `pg_has_role(current_user, 'expense_setup', 'MEMBER')` returns `false` on the app pool, (c) the setup pool can INSERT without an `app.current_user_id` set, and (d) the app pool returns zero rows without one.

### Evolution

**v1.0 design (superseded).** A single Hikari pool connecting as `expense_app`. `expense_setup` was `NOLOGIN`; the three setup methods called `RoleElevationService.elevateToSetupRole()` to issue `SET LOCAL ROLE expense_setup` at the start of their transaction. This worked but `expense_app` held membership in `expense_setup`, meaning any SQL injection through the app role could escalate by issuing the same statement. The trade-off was acknowledged at the time and scheduled for v2.0.

**v2.0 fix (S1, V20).** Two pools, `RoleElevationService` deleted, membership revoked. The textbook-secure version that the v1.0 ADR pointed to as future work.

## Alternatives considered

- **Application-only filtering (Layer 1 alone).** Rejected — one bug leaks data.
- **DB RLS only (Layer 3 alone).** Would work, but Layer 2 is what bridges application context to DB context. Without it, the session variable would be set with `SET` (session-level), and HikariCP's connection reuse would leak the variable across users on the same connection.
- **Tenant-per-schema isolation.** Considered. Stronger isolation but very heavy ergonomics — every connection picks a schema, every migration runs N times, every join across schemas is awkward. Not warranted at the personal-finance scale.
- **Permissive `WITH CHECK (TRUE)` for INSERT on `users` and `bank_accounts`.** Rejected as the way to handle pre-auth operations. Permissive policies create real holes that anyone with the app role can exploit (e.g., anonymous attacker creating bank accounts for arbitrary user IDs). The setup role pattern keeps the original policy strict and isolates the escape hatch behind a NOLOGIN role.
