# Testing strategy

> **Context:** Expands [overview.md](../overview.md) on how the system is verified. Backreferences the non-functional qualities in [requirements/non-functional.md](../requirements/non-functional.md) (especially N1/N2 isolation, N7 tamper-evidence) and the RLS design in [ADR-0011](../decisions/0011-three-layer-rls-defence.md). Written in the 2026-06-04 test-hardening pass — see the "Why this document exists" section.

This document describes how the codebase is tested, the patterns to follow when adding tests, and the non-obvious traps that cost real debugging time. It is the reference a contributor should read before writing a new integration test.

---

## Why this document exists

For most of v1.0–v2.0 the test suite **did not run at all**. No `build.gradle` configured `useJUnitPlatform()`, so Gradle's default JUnit-4 runner discovered zero of the JUnit-5 (Jupiter) tests. `./gradlew test` passed in seconds having executed nothing, and CI was green for the wrong reason. The tests compiled (so they tracked the code) but never executed (so they verified nothing).

Turning the suite on (2026-06-04) surfaced **eight production bugs** that had shipped because nothing exercised them — including two security-relevant RLS defects that made entire v2.0 features (delegation, CSV import) silently non-functional over the application connection. See [Appendix: bugs the suite caught](#appendix-bugs-the-suite-caught). That episode is the reason this document is explicit about *running* tests, not just writing them.

The single most important rule: **a test that never executes is worse than no test — it is a false sense of safety.** CI must run the full suite, and a green build must mean the suite ran.

---

## The test pyramid

Four layers, fastest/cheapest first.

| Layer | Tooling | What it covers | Needs Docker? |
|---|---|---|---|
| **Unit** | JUnit 5 + Mockito | Pure domain logic and service impls with collaborators mocked — weight maths, validation, lockout counting, JWT, CSV parsers, the cleanup job | No |
| **Architecture** | ArchUnit | Module-boundary rules and the sealed `bankintegration` package | No |
| **Slice** | `@WebMvcTest` | Controller request validation (400s) in isolation from the service/DB | No |
| **Integration** | `@SpringBootTest` + Testcontainers Postgres | The full wiring against a real Postgres with RLS, triggers, partitions, and the two connection pools | **Yes** |

The integration layer is the centre of gravity. The whole point of the architecture — RLS, the hash chain, the audit triggers, partitioned `expenses`, the two-pool design — only exists in a real Postgres, so the tests that matter most run against one.

### Unit tests

Plain JUnit 5 + Mockito, no Spring context. Located beside the class under test. Conventions:

- **Strict stubbing.** Mockito's default strict stubbing is kept on. An unused stub fails the test (`UnnecessaryStubbingException`) — this is desirable; it catches tests that drift from the code they pretend to exercise. Do not stub `clock.getZone()` when the code only calls `clock.instant()`.
- **Fixed `Clock`.** Time-dependent code takes a `java.time.Clock` constructor parameter; unit tests inject `Clock.fixed(...)` for determinism. `JwtService` validates token expiry against its injected clock (not jjwt's wall-clock default) precisely so this works.

### Architecture tests

`ArchitectureTest` (ArchUnit) enforces the [module boundaries](module-boundaries.md): `core` depends on nothing Spring; `api` and `adapters` don't form cycles; and `com.finance.bankintegration` is **sealed** — nothing outside the package may depend on its internals. That last rule is why `BankIntegrationProperties` is enabled by a `@Configuration` *inside* the package rather than on `ApiApplication`.

### Slice tests

`ExpenseControllerValidationTest` uses `@WebMvcTest` to verify Bean Validation rejects bad requests (negative amounts, non-UUID idempotency keys, bad enums) with `400` before any service runs. A `@WebMvcTest` still instantiates the app's `@Component` servlet filters, so their collaborators (`JwtService`, `SudoTokenService`) are `@MockBean`-ed and the `app.jwt.*` properties supplied so `JwtProperties` binds.

### Integration tests

`@SpringBootTest` boots the real application context wired to a Testcontainers Postgres. Two base classes:

- **`IntegrationTestBase`** — `WebEnvironment.MOCK`. Service-level tests that call services directly: `AuthFlowIntegrationTest`, `AccessGrantIntegrationTest`, `SudoTokenIntegrationTest`, `AuditTrailIntegrationTest`, `RefreshTokenIntegrationTest`, `SetupServiceIntegrationTest`, `PoolIsolationIntegrationTest`.
- **`WebIntegrationTestBase`** (extends the above) — `WebEnvironment.RANDOM_PORT` + `TestRestTemplate`. Tests that must exercise the real HTTP filter chain (`TraceIdFilter → JwtAuthenticationFilter → AsUserIdFilter → controller`): `CsvImportIntegrationTest`, `CsvImportConnectionIntegrationTest`, `DelegationIntegrationTest`.

Choose `WebIntegrationTestBase` only when the test depends on HTTP-level behaviour (status codes, headers, the filter chain, delegation). Otherwise use `IntegrationTestBase` — booting the servlet container is unnecessary cost.

---

## Integration-test infrastructure

### Singleton Testcontainers Postgres

`IntegrationTestBase` starts **one** Postgres container for the whole JVM, in a static initializer, and never stops it:

```java
static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("test").withUsername("postgres")
                .withPassword("test_superuser_password")
                .withInitScript("test-init.sql");
static { POSTGRES.start(); }   // never stopped — Ryuk reaps it at JVM exit
```

It does **not** use the JUnit `@Testcontainers`/`@Container` lifecycle. With `@Container` on a `static` field in a *shared base class*, JUnit stops the container after the first test class finishes, but Spring's `TestContext` framework caches the `ApplicationContext` (and the Hikari pools bound to the then-current mapped port) and reuses it for later classes — which then hit a dead port and fail en masse with `Connection refused`. The singleton pattern keeps the mapped port stable for every cached context. (See bug #1 in the appendix.)

### Role and pool setup mirrors production

`test-init.sql` creates the `expense_app` login role before Flyway runs; Flyway then applies all migrations as the container superuser (`postgres`). The app then runs with the same two-pool design as production ([ADR-0011](../decisions/0011-three-layer-rls-defence.md)):

- **app pool** → connects as `expense_app` (non-owner) → **RLS is enforced**.
- **setup pool** → connects as the superuser/owner → **bypasses RLS** (Option-A: owner-based bypass).

This split is what makes the tests faithful: a test reading via the app pool sees exactly what a real authenticated request would see.

---

## Writing integration tests: the RLS patterns

RLS is the thing most likely to make a new test mysteriously return zero rows or throw `42501`. Use these patterns; they encode hard-won lessons.

### `setupJdbc()` — seed and verify, bypassing RLS

For inserting fixtures and for "did the service persist X?" assertions, query through the **setup pool**, which bypasses RLS:

```java
setupJdbc().execute("TRUNCATE ... RESTART IDENTITY CASCADE");
setupJdbc().update("UPDATE users SET is_discoverable = TRUE WHERE id = ?", id);
Integer count = setupJdbc().queryForObject(
        "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
```

This is simpler than juggling `app.current_user_id` and immune to the GUC-pollution trap below. It verifies *persistence*, not RLS visibility — use the app-pool helpers below when the test's point is RLS behaviour.

### `runAs(userId, …)` / `withAppUser` — operate under a user's RLS context

When a test calls a service that must run as a specific user (so RLS scopes correctly and the audit trigger records the right actor), set `app.current_user_id` **inside a transaction the service joins**, and set it through the **same connection Hibernate uses**:

```java
private void runAs(UUID userId, Runnable body) {
    appTx.executeWithoutResult(status -> {
        setRlsUser(userId);   // via the EntityManager — see below
        body.run();           // service @Transactional joins this tx
    });
}
private void setRlsUser(UUID userId) {
    entityManager.createNativeQuery("SELECT set_config('app.current_user_id', :uid, true)")
                 .setParameter("uid", userId.toString()).getSingleResult();
}
```

Two non-negotiable details, each of which cost a debugging cycle:

1. **Set the GUC through the `EntityManager` (or a JdbcTemplate bound to the same tx), not a bare `JdbcTemplate`.** A standalone `JdbcTemplate.execute("SET LOCAL …")` resolves to a *different pooled connection* than the one Hibernate uses for the service's INSERT, so the context never reaches the write. The diagnostic that proved this: `current_setting(...)` read back `grantor` on the test's connection, yet the service's INSERT still failed RLS — different connections. (Bug catalog, "EntityManager vs JdbcTemplate".)
2. **`RlsSessionAspect` skips when there is no `SecurityContext`.** In a direct service call there's no authenticated principal, so the aspect does nothing and the value *you* set with `SET LOCAL` stands. (Do **not** try to drive these tests by populating the `SecurityContext` and relying on the aspect — that path did not set the context for direct calls. HTTP tests are different: there the JWT filter populates the context and the aspect fires for real.)

### Throwing-service tests put the assertion OUTSIDE `runAs`

A service method that throws marks the surrounding transaction rollback-only. If `assertThatThrownBy` is *inside* the `runAs` callback, the callback returns normally (the exception was caught) and `TransactionTemplate` then throws `UnexpectedRollbackException` on commit — the wrong exception. Put the assertion outside:

```java
// WRONG — fails with UnexpectedRollbackException
runAs(u, () -> assertThatThrownBy(() -> svc.create(bad)).isInstanceOf(X.class));

// RIGHT — the real exception propagates and TransactionTemplate re-throws it
assertThatThrownBy(() -> runAs(u, () -> svc.create(bad))).isInstanceOf(X.class);
```

If the method rejects its input *before* any RLS-scoped query (e.g. a null/blank guard), no context is needed at all — call the service directly without `runAs`.

### Cross-user reads need a SECURITY DEFINER function, not RLS

A request runs as one user, but some features must read *another* user's row (delegation resolves a grantee by username; listing grants resolves the counterparty's username). The `users` RLS policy exposes only the caller's own row, so a normal repository read returns nothing. These go through SECURITY DEFINER functions ([V33](../../adapters/src/main/resources/db/migration/V33__discoverable_user_lookup_functions.sql): `find_discoverable_user`, `username_of`) that run as the table owner, bypass RLS, and return **only** id/username. Tests don't special-case this — they just exercise the real path.

---

## The CI pipeline

`.github/workflows/ci.yml`, on push/PR to `main`:

1. Set up Java 21 (Temurin), cache Gradle.
2. **Build** `:api:bootJar :worker:bootJar -x test` (fast compile check).
3. **`./gradlew test --no-daemon --continue`** — the real suite, unit + Testcontainers integration. `--continue` runs every module's tests even after one fails, so a single run reports *all* failures instead of stopping at the first.
4. Build the API and Worker Docker images.

`useJUnitPlatform()` is configured once in the root `build.gradle` `subprojects` block (with full-stack exception logging), so every module's `Test` task uses the JUnit 5 platform. **Without it the entire suite is silently skipped** — that line is load-bearing.

Timing: a warm run is ~1 minute (the Postgres image and Spring `ApplicationContext`s are cached, so the ~14 integration classes share a handful of contexts). A cold run — first image pull, no caches — is 15–22 minutes. A green build under ~10 seconds means the tests didn't run; treat that as a failure.

GitHub Actions provides a Docker daemon on `ubuntu-latest`, so Testcontainers works without extra setup. The suite also runs locally with the documented prerequisites (Java 21 + Docker — see [local-development.md](../operations/local-development.md)), but CI runs it on every push and is the authoritative gate. Because a contributor's workstation may not have a working Docker daemon (so they can't run the integration layer locally), CI being trustworthy — i.e. actually executing the suite — is essential.

---

## Gotchas (quick reference)

| Symptom | Cause | Fix |
|---|---|---|
| `gradle test` green in seconds, nothing verified | `useJUnitPlatform()` missing → JUnit-4 runner finds no Jupiter tests | Configure it in root `subprojects` |
| `Connection refused` across many classes | `@Container` stops the container between classes; cached Spring contexts keep the dead port | Singleton container in a `static {}` block |
| Service INSERT fails RLS though `current_setting` shows the right user | `JdbcTemplate` `SET LOCAL` is on a different pooled connection than Hibernate's | Set the GUC via the `EntityManager` |
| `invalid input syntax for type uuid: ""` | A pooled connection retains `app.current_user_id = ''` after a prior `SET LOCAL`; the RLS cast chokes on `''` | Policies use `NULLIF(current_setting(...), '')` ([V32](../../adapters/src/main/resources/db/migration/V32__rls_fail_closed_on_empty_context.sql)); seed/verify via `setupJdbc()` |
| `new row violates row-level security policy` with the right context | Policy was `AS RESTRICTIVE` with no permissive policy = default-deny | Policies are `PERMISSIVE` ([V34](../../adapters/src/main/resources/db/migration/V34__rls_policies_permissive_not_restrictive.sql)) |
| `UnexpectedRollbackException` from a "should throw" test | `assertThatThrownBy` inside a `runAs` transaction | Move it outside `runAs` |
| HTTP `404` from a read endpoint that should find data | Read method not `@Transactional` → aspect never set RLS context | Add `@Transactional(readOnly = true)` |
| `Invalid HTTP method: PATCH` | `TestRestTemplate`'s default JDK client rejects PATCH | Put `httpclient5` on the test classpath |
| `CATEGORY_NOT_FOUND` for a seeded category | `TRUNCATE users CASCADE` also truncates `categories` (FK to users), wiping V14 system rows | Re-seed needed system categories in `@BeforeEach` |

---

## Coverage map

| Area | Test classes |
|---|---|
| Auth — register/login/lockout/timing defence | `AuthFlowIntegrationTest`, (`PostgresAuthService` paths) |
| Refresh-token rotation + reuse cascade | `RefreshTokenIntegrationTest` |
| Default-account setup | `SetupServiceIntegrationTest` |
| RLS pool isolation (the security regression test) | `PoolIsolationIntegrationTest` |
| Row-level audit trail (S5) | `AuditTrailIntegrationTest` |
| Delegation D1 / D2 / D3 | `AccessGrantIntegrationTest`, `SudoTokenIntegrationTest`, `DelegationIntegrationTest` |
| CSV import end-to-end (upload → async → status → hash chain → dedup → rate-limit → startup recovery) | `CsvImportIntegrationTest` |
| CSV connection CRUD | `CsvImportConnectionIntegrationTest` |
| CSV parsers (per-bank formats) | `CbaCsvParserV1Test`, `UbankCsvParserV1Test`, `QudosCsvParserV1Test` |
| Service-impl business logic | `PostgresExpenseServiceTest`, `PostgresCategoryServiceTest`, `PostgresTargetServiceTest`, `PostgresUserServiceTest` |
| JWT issue/validate | `JwtServiceTest` |
| Worker cleanup job | `CleanupJobTest` |
| Controller validation | `ExpenseControllerValidationTest` |
| Module boundaries | `ArchitectureTest` |

`PoolIsolationIntegrationTest` is the load-bearing security test: it proves the app pool returns zero rows without a user context (fail-closed) and cannot escalate to the setup role.

---

## Appendix: bugs the suite caught

When first run, the suite found eight production bugs. They are recorded here because they are the evidence for every rule above, and a few are security-relevant enough to be worth knowing.

1. **Account lockout never engaged.** `login()` recorded the failed attempt and applied the lockout, then threw `InvalidCredentialsException` — which rolled back the same transaction, discarding the record. Fixed by recording in a `REQUIRES_NEW` bean (`LoginFailureRecorder`) that commits independently of the rollback.
2. **RLS errored instead of failing closed.** Missing/empty context raised an error rather than returning zero rows, contradicting [ADR-0011](../decisions/0011-three-layer-rls-defence.md)'s fail-closed claim. Fixed in [V32](../../adapters/src/main/resources/db/migration/V32__rls_fail_closed_on_empty_context.sql) with `NULLIF(current_setting('app.current_user_id', TRUE), '')`.
3. **`FORCE ROW LEVEL SECURITY` blocked owner-bypass.** Under Option-A the setup pool relies on owner-bypass, which `FORCE` defeats. Fixed in [V31](../../adapters/src/main/resources/db/migration/V31__setup_pool_owner_bypass_no_force_rls.sql) for `refresh_tokens`/`csv_imports`.
4. **Delegation couldn't find discoverable users.** The grantee lookup ran on the app pool under the caller's RLS context, which hides other users. Fixed with SECURITY DEFINER functions ([V33](../../adapters/src/main/resources/db/migration/V33__discoverable_user_lookup_functions.sql)).
5. **v2.0 RLS policies were `RESTRICTIVE`-only = default-deny.** With no permissive policy, the app pool could not read or write `access_grants`, `sudo_tokens`, `csv_imports`, `csv_import_connections`, or `raw_bank_transactions` at all — delegation and CSV import were fully broken over the app pool. Fixed in [V34](../../adapters/src/main/resources/db/migration/V34__rls_policies_permissive_not_restrictive.sql). The biggest one.
6. **CSV read paths weren't `@Transactional`.** `CsvImportService.upload()/status()` and `CsvImportConnectionService.get()` ran without a transaction, so `RlsSessionAspect` never set the context → `404`. Fixed by adding `@Transactional` (and moving the async kickoff to an `afterCommit` callback so the row is committed before the async thread reads it).
7. **Spring wiring defects:** a `SecurityConfig ↔ AsUserIdFilter` bean cycle, filter-registration order, the setup-pool beans binding to the `@Primary` app pool (fixed with `@Qualifier`), and the primary JPA tx manager needing the bean name `transactionManager` for Spring Data.
8. **`JwtService` validated against the wall-clock**, not its injected `Clock`, so fixed-clock tests saw fresh tokens as expired. Fixed by passing the clock to jjwt's parser.

The through-line: every one shipped because the path was never executed. The suite, now that it runs, is the guard against the next one.
