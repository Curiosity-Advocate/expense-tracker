## ADR-0020 — CSV import architecture

> **Context:** Covers the v2.0 B1 CSV import implementation in detail. Companion to [ADR-0019](0019-basiq-credential-model.md) (which justifies why CSV over an aggregator at this stage). Driven by F4 (extended), N6, N7, N8, N10, N21.

**Status:** Accepted. Adopted in v2.0 (B1, 2026-06-01).

---

## Context

ADR-0019 commits us to CSV import as v2.0's bank-data pipeline. This ADR captures the architectural decisions made during the B1 implementation — eleven of them, each a fork that was deliberated and resolved in conversation:

1. Module isolation level
2. Date-dispatched parser version selection
3. Per-source connection tables
4. Active-source-at-a-time policy (deferred to v3.0)
5. Sync vs async processing
6. Spring `@Async` in API vs worker module
7. Per-batch transactions
8. Hash chain in DB trigger, not application code
9. Per-user advisory lock (not SERIALIZABLE) for hash-chain serialisation
10. CSV bytes in a DB BYTEA column
11. Startup recovery via setup pool

## Decision

### 1. Module isolation enforced by ArchUnit

The bank-integration code lives under `com.finance.bankintegration..` across all three Gradle modules:

```
core/.../bankintegration/          ports, domain records, exceptions, TransactionFingerprint
adapters/.../bankintegration/      entities, repos, parsers, service, async processor, recovery
api/.../bankintegration/           controllers, DTOs, exception handler
```

An ArchUnit rule `bankintegration_is_internally_sealed` enforces: **no class outside `com.finance.bankintegration..` may import from inside it.** This makes the module a sealed unit — adding/replacing a bank-integration strategy is contained, and `rm -r com/finance/bankintegration/<strategy>/` is a viable delete operation.

The seal is one-directional: bank-integration code may import from `com.finance.entity..`, `com.finance.service..` etc., but nothing flows back in. `BankIntegrationExceptionHandler` (a sibling `@RestControllerAdvice`) handles bankintegration-specific exception translations so the global `GlobalExceptionHandler` stays unaware of bankintegration internals.

### 2. Date-dispatched parser version selection

Each `CsvBankParser` declares:

```java
String bankId();                     // "cba", "anz", etc.
LocalDate validFromDate();           // earliest export date this parser handles
String versionTag();                 // "csv_cba_v1" — stamped on raw_bank_transactions.source_format
```

The upload service picks the parser via `CsvParserRegistry.pickByDate(bankId, exportedOnDate)` — the parser whose `validFromDate` is the latest one `<=` the user-supplied export date. When a bank changes their CSV format on a known date, we ship a new parser version (e.g. `CbaCsvParserV2` with `validFromDate = 2027-04-15`); existing connections keep working with the old parser for older exports and the new one for newer ones. **The connection table doesn't store a parser version** — only `bank_id`. Format-revision changes don't require any user-side config change.

**Rejected alternative:** stored `source_format = "csv_cba_v1"` on the connection. Would require PATCHing every connection on format revision, with risk of user uploading new-format CSV before patching.

### 3. Per-source connection tables (not a unified table)

`csv_import_connections` is a CSV-only table. When v3.0 adds an aggregator, it adds a sibling `basiq_import_connections` (or similar) — not new columns on a unified table.

| | Pros | Cons |
|---|---|---|
| **Per-source tables (chosen)** | Type-safe columns; each source declares exactly its required fields; NOT NULL constraints work properly; deleting a source = drop a table | One table per source |
| Unified table with JSON state | One table; flexible | Loses type safety; CHECK constraints can't reach into JSON; column bloat with NULLs |
| Unified table with all-nullable columns | One table | NULL bloat; conditional CHECK constraints are ugly |

User instinct ("if there are multiple tables, how do we enforce one-active-source?") was addressed by the active-source-at-a-time policy (#4) — the multi-table design doesn't make uniqueness harder, the cross-source dedup question is what does, and that's a separate concern handled at the expenses layer (B4 fuzzy match).

### 4. Active-source-at-a-time policy (cutover semantics, deferred to v3.0)

When v3.0 adds an aggregator, the rule will be: **a `bank_account` has at most one active import source at any time**. Switching from CSV to aggregator is a deliberate operation that:

1. Marks the existing CSV connection inactive
2. Determines a cutover date = max date in `raw_bank_transactions` for this account
3. Creates the aggregator connection with `cutover_from_date = cutover + 1 day`
4. Aggregator imports only from `cutover_from_date` forward — zero overlap with CSV data

This sidesteps cross-source dedup (which is genuinely hard because descriptions normalise differently across sources). The relevant columns (`is_active`, `cutover_from_date`) and the cross-table trigger arrive in v3.0; v2.0 doesn't ship them since only one source exists.

### 5. Sync at the API boundary, async inside

`POST /api/v1/bank-accounts/{id}/csv-import` returns 202 Accepted immediately. The user polls `GET /api/v1/bank-data/csv-imports/{id}` for status. Behind the scenes a Spring `@Async` thread processes the upload.

**Why not fully sync (request blocks until done):** for a 5000-row CSV the upload would take seconds; clients would time out under realistic load.

**Why not fully sync at the *parsing* layer with async only at *persistence*:** would mean the upload endpoint reads the whole file before responding (still slow for large CSVs); also splits responsibility awkwardly.

**Why not stream and respond as we go (SSE / chunked):** more complex client-side; doesn't fit the existing JSON-everywhere pattern.

**Upload transaction boundary (corrected 2026-06-04).** `upload()` is `@Transactional`: the connection lookup, rate-limit check, and the PENDING `csv_imports` INSERT are all RLS-scoped, so the method needs `RlsSessionAspect` to set `app.current_user_id` — which the aspect only does for `@Transactional` methods. The original implementation left `upload()` (and `status()` / the connection `get()`) **non-transactional** on the theory that each repository call would manage its own tx; the consequence was that no RLS context was ever set and every upload and status read returned `404`. (It never surfaced because the CSV integration tests weren't running — see [testing-strategy.md](../architecture/testing-strategy.md).) The async kickoff that still has to happen *after* the row is committed (so the async thread sees it) is now registered as an `afterCommit` transaction synchronization rather than relying on the method not being transactional.

### 6. `@Async` in the API process, not the worker module

Considered moving CSV processing to the worker module via a job-queue table (the same pattern B3's normalisation worker uses). Rejected because:

- Worker module depends only on `core`, not `adapters`. The parsers live in `adapters/bankintegration/parser/`. Moving parsers to `core` would require either (a) dropping the Spring `@Component` annotations (and hand-registering 6 `@Bean`s in two places) or (b) adding `adapters` as a worker dep (drags JPA + everything into the worker JVM).
- For personal-use scale (10 users, weekly imports), API restarts mid-import are rare. Startup recovery (#11) handles the rare case.

The eventual aggregator integration in v3.0 may go to a job-queue model — at that point we'd introduce a generic `jobs` table that B3's normalisation worker also uses.

### 7. Per-batch transactions, with loose-bundled completion

The processor commits in batches of N rows (default 100, configurable via `bank-integration.csv.batch-size`). Each batch is its own transaction; if the JVM dies between batches, startup recovery picks up the row, resets to PENDING, and re-runs from scratch — `ON CONFLICT DO NOTHING` on `(user_id, external_transaction_id)` makes the rerun idempotent.

After the last batch, a separate "completion" transaction:
1. Sets `csv_imports.status = COMPLETED`, sets `completed_at`, clears `raw_csv_bytes` (the upload-time bytes), sets `raw_csv_bytes_deleted_at`
2. If at least one row was imported (`imported_count > 0`), updates `csv_import_connections.last_imported_at = NOW()` and `last_date_to = max(date)`

Both operations happen in the same app-pool transaction so they atomically reflect "import is done." If a crash happens between the last batch commit and the completion transaction, recovery re-runs the whole import; dedup catches the already-inserted rows.

**Rejected alternative:** one big transaction for the whole import. Wins atomicity but holds row locks longer and loses incremental visibility into progress (the status endpoint shows running counters that would otherwise be invisible until the transaction commits).

### 8. Hash chain implemented in a DB trigger

`raw_bank_transactions` is append-only with a per-user hash chain (each row's `current_hash = SHA-256(prev_hash || raw_payload || user_id || external_transaction_id)`). The chain is computed by the BEFORE INSERT trigger `compute_raw_bank_transaction_hash` (V26), not by application code.

**Why in the DB:** the integrity guarantee holds regardless of who is inserting — application code, worker, future migrations, superuser scripts. Moving the computation to application code would mean the integrity invariant lives in only one of the paths that can write to the table.

### 9. Per-user advisory lock (not SERIALIZABLE) for chain serialisation

Concurrent inserts for the same user must serialise to prevent the chain from forking. Three options:

| Option | Pros | Cons |
|---|---|---|
| `SET TRANSACTION SERIALIZABLE` | Standard SQL | Locks the whole transaction's reads, not just the chain; surfaces as `40001` retryable errors |
| `SELECT … FOR UPDATE` on tail row | Row-scoped locks | Doesn't handle first-row case; brittle read-modify-write under lock |
| **`pg_advisory_xact_lock(hashtextextended(user_id::text, 0))` (chosen)** | Scope is exactly "this user's chain"; works for first-row case; auto-released on commit/rollback; lives inside the trigger so the lock follows the table | Postgres-specific (already committed via ADR-0002) |

The 64-bit hash key can theoretically collide across users, causing two unrelated users to briefly wait on each other's chain insert — correctness is preserved, just a microsecond of latency. At our scale this is essentially impossible.

### 10. CSV bytes in a DB BYTEA column

The async boundary means the upload request is gone by the time the processor runs; the file bytes have to live somewhere durable. `csv_imports.raw_csv_bytes` is a NOT NULL BYTEA bounded by the 10 MB upload cap. Cleared (UPDATE to empty bytea + `raw_csv_bytes_deleted_at = NOW()`) the moment status reaches COMPLETED or FAILED — the CHECK constraint `chk_bytes_deleted_only_on_terminal` enforces this.

**Rejected alternatives:**
- In-memory (passed to `@Async`): lost on JVM restart between submission and processing
- Filesystem (Render's disk): platform-specific; container restarts lose ephemeral disk
- S3-compatible store: extra dependency for personal-use scale

### 11. Startup recovery via setup pool

On API startup, `CsvImportStartupRecovery` (`ApplicationRunner`) scans `csv_imports` for rows in PENDING or RUNNING with `submitted_at < NOW() - stale_threshold` (default 10 minutes). For each match: reset to PENDING + clear `started_at` + zero counters, then call `processor.kickoff(importId)`.

The scan runs without any user context (there's no JWT at process start), so it goes through the **setup pool** (RLS-bypassing — BYPASSRLS in the v2.0 design, owner-bypass under the Option-A pivot for managed Postgres; see [ADR-0011](0011-three-layer-rls-defence.md)). V29 grants `SELECT, UPDATE` on `csv_imports` to `expense_setup`; those grants are vestigial under Option A but kept to document intent. The actual import work then runs on the **app pool** with `app.current_user_id` set from each row's `user_id` (the processor does this as the first statement of every batch transaction).

## Consequences

**Positive:**

- The bank-integration module is genuinely isolated. Future strategies (aggregator, AI-assisted parsing, etc.) plug in alongside without touching existing code.
- DB schema is source-agnostic — `raw_bank_transactions`, `dead_letters`, the hash chain — all serve future sources too.
- Tamper-evidence (N7) holds because the hash chain is in a DB trigger, not application code.
- Async + per-batch + startup recovery means a 5000-row CSV completes in seconds and survives JVM restarts.
- Per-account rate limit (one successful import per 7 days) is enforced at the schema layer (`csv_imports` indexed predicate), not via Bucket4j.

**Negative:**

- The module is **untested at the parser-correctness level for ANZ, AMP, and Suncorp** — those parsers used best-guess formats during B1.4 and need real CSV samples to verify. CBA, Ubank, and Qudos parsers have unit tests against verified samples.
- The setup pool's RLS-bypass reach grew to include `csv_imports` (was only `users`, `bank_accounts`, `user_login_failures`, `refresh_tokens`, `sudo_tokens` for the auth flows). Each addition needs scrutiny — see ADR-0011. Under the Option-A pivot the per-table grants are vestigial (owner-bypass is unconditional), which makes this point *more* important: the audit lives in the migration files, not in Postgres-enforced permissions.
- Startup recovery resets counters to 0 on retry, which means displayed `dedupedCount` after a retry is inflated (counts re-imports of previously-inserted rows as dedupes). Documented behaviour; acceptable for personal scale.
- **Shipped non-functional until 2026-06-04.** `csv_imports`, `csv_import_connections`, and `raw_bank_transactions` were created with `AS RESTRICTIVE` RLS policies and no permissive policy = default-deny, so the app pool could not touch them; combined with the non-`@Transactional` read paths (above), the entire CSV flow returned `404`. Fixed in V34 (permissive policies — see [ADR-0011](0011-three-layer-rls-defence.md)) and by adding `@Transactional`. The `CsvImportIntegrationTest` end-to-end test now passes.

**Neutral:**

- Java SDK ergonomics: parsers use OpenCSV (5.9). One bank's format change = one parser file update + one CHECK-constraint migration listing the new version tag. No central dispatch table to keep in sync.
- The choice of `@Async` over a worker job queue means CSV import doesn't reuse the worker's existing `JobFailureAlerter` retry-with-backoff. That's a future-revisit if processing failures become frequent enough to need email alerting.

## Forward references

- **B3 (normalisation worker)** consumes `raw_bank_transactions` rows by dispatching on `source_format` to pick the right per-format normaliser. The B3 design is unchanged by the v2.0/v3.0 source split — it sees a uniform table.
- **B4 (duplicate detection)** runs at the `expenses` layer with fuzzy matching. The active-source-at-a-time policy (#4) means cross-source duplicates are rare by construction; B4's job becomes "merge probable-duplicate expenses from manual + bank-import paths" rather than "reconcile two aggregator views of the same transaction."
- **B7 (dead-letter API)** uses the `dead_letters` table that B1 created. The schema accommodates B3 (`NORMALISE`) and future jobs via the `job_type` discriminator — a follow-up migration adds new allowed values.

## Alternatives summary table

| Decision | Chosen | Rejected |
|---|---|---|
| Parser version dispatch | By export date | Stored on connection table |
| Connection table layout | Per-source tables | Unified table with JSON or all-nullable cols |
| Processing model | Async via `@Async` in API + startup recovery | Sync; or worker job queue |
| Transaction boundaries | Per-batch + completion bundle | One big tx; or per-row tx |
| Hash chain location | DB trigger | Application code |
| Hash chain serialisation | Per-user advisory lock | `SERIALIZABLE` isolation; `SELECT FOR UPDATE` |
| CSV byte storage | DB BYTEA | In-memory; filesystem; S3 |
| Startup recovery access | Setup pool (RLS-bypassing — see ADR-0011) | Superuser; new dedicated pool |
| Module boundary enforcement | ArchUnit (compile-checked) | Conventions only |
