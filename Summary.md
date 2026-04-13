## Session Summary

---

### 1. Functional Requirements

**Expense tracking:** Amount, merchant name, date mandatory. Categories optional, multi-select, evenly weighted. Payment method, notes, bank account optional. Currency always AUD. Soft deletes only — records never physically removed.

**Querying:** Filter by date range, merchant, category, payment method, bank account, amount threshold. Group by category, merchant, bank account, month. All filters combinable.

**Targets:** Per category per month and total per month. Reports dollar spent, dollar remaining, percentage used, and end-of-month projection in dollar amount.

**Bank integration:** Manually triggered only. Raw data stored append-only with hash chaining. Duplicate detection flags but preserves both records in MVP. Merge is a user action with immutable audit trail.

**AI categorisation:** Suggestions presented to user for confirmation. Accept or override with before/after category structure. AI acceptance vs override tracked for future model improvement.

**Users:** Strict data isolation via RLS. Opt-in discoverability before grants. Bulk access grants, READ\_ONLY or FULL, 1–10 day expiry. Username/password now, Google OAuth and TOTP MFA as future upgrades.

**Observability:** Failed jobs go to dead letter table after 3 retry cycles. Dead letters surfaced via API. Manual re-trigger available.

---

### 2. Non-Functional Requirements

**Performance:** Sustained 5,000 req/min reads, burst 15,000 req/min via read replica. Dashboard P95 under 800ms. Prediction P95 under 2s. Write P95 under 300ms.

**Consistency:** Strong consistency on write path (ACID). Bounded eventual consistency on reads, maximum 60 second staleness. Materialized views refreshed on write at low volume, scheduled at 60s intervals under load.

**Availability:** MVP tolerates downtime. V1 upgrade via Wake-on-LAN on home Linux server. Future target 99.9% uptime (three nines) on AWS.

**Reliability:** Exponential backoff per retry (1m → 2m → ... capped at 2h), 10 attempts per cycle, 3 cycles with 24h gap between cycles. Dead letter after cycle 3 exhausted.

**Retention:** Records never deleted. Expenses older than 5 years archived to cold PostgreSQL partition. Archive boundary dynamically computed as `current year − 5`.

**Security:** RLS enforced at PostgreSQL layer. Hash chaining on raw bank ingestion. Immutable merge audit fields via DB trigger. 7-day tokens in V1. Step-up sudo token required for cross-user data access. HSTS headers. Certificate pinning in V2.

**Observability V1:** Structured JSON logging to file with rotation. Dead letter table as primary failure visibility. Active alerting as future state.

---

### 3. Services and Components

**Two deployable processes** sharing a `core` Gradle module of interfaces and domain objects.

**API process** hosts: API gateway filter, Auth module, User Management module, Expense module, Target and Prediction module, Bank Integration module (HTTP side), AI Categorisation module (HTTP side), Dead Letter module.

**Worker process** hosts: Bank Integration worker (sync jobs, raw ingestion, hash chaining, normalisation), AI Categorisation worker (LLM calls, suggestion writes), Archival worker (partition creation each December, archival each January), Job queue consumer using `FOR UPDATE SKIP LOCKED`.

**Communication pattern:** Workers call service interfaces from `core`, never SQL directly. DB is the shared integration point. No HTTP calls between processes. Workers write to DB through service layer; API reads from same DB. Job queue is a PostgreSQL table in V1, evolving to Redis Streams.

**Async notification:** Observer pattern in V1 (mobile polls `/suggestions/pending`). FCM push in V2 via `ApplicationEventPublisher` listener — additive change, no job code changes required.

---

### 4. API Contract

**27 endpoints across 7 modules.** Key design decisions:

- All endpoints versioned under `/api/v1`
- Uniform error envelope with `traceId`, uniform paginated collection envelope
- `202 Accepted` for async operations (bank sync trigger)
- `idempotencyKey` on expense creation for safe client retries
- `X-Sudo-Token` header required on any `asUserId` cross-user request
- Bank sync rate limited at 5 per user per 24h via Bucket4j, rate limit headers returned on every sync response
- `strategyName` and `strategyVersion` returned in every prediction response
- `dataFreshAsOf` timestamp returned on all materialized view reads
- AI suggestion resolution uses `original`/`target` category structure as optimistic lock
- Duplicate resolution accepts `primarySourceId` and `duplicateIds` as list
- Bank account connection uses desktop localhost OAuth listener with CSRF state token
- `202 Accepted` for async operations, `207 Multi-Status` for partial grant success deferred to future

---

### 5. Module Boundaries

**One overarching rule:** Dependencies point inward toward domain, never outward toward infrastructure. Domain interfaces never import Spring annotations, JDBC, or HTTP clients.

**Each module owns its tables exclusively.** No module queries another module's tables directly. All cross-module access is via published service interfaces in `core`.

**Key ownership decisions:**
- Expense module owns category weight computation — callers send category list, module computes even split
- Target module reads only from materialized views via `ExpenseService.getSummary`, never raw expense tables
- Bank worker calls `ExpenseService.importBankTransaction` through interface — never writes to `expenses` directly
- AI worker calls `ExpenseService.applyCategorizationSuggestion` through interface — conflict detection lives inside Expense module, not AI module
- Archival module touches only partition registry metadata — never reads expense records
- All modules write to `ObservabilityService` — nothing reads back from it except the API and operators
- `UserPrincipal` injected by gateway filter — no module calls `AuthService` during a live request

**Gradle module structure:** `core` (interfaces + domain objects) → `api` (implements interfaces, serves HTTP) and `worker` (calls interfaces, processes jobs). `core` has no dependency on `api` or `worker`.

---

### 6. Low-Level Design

**Hash chaining:** Each `raw_bank_transactions` row stores `content_hash` (SHA-256 of row content) and `chain_hash` (SHA-256 of content hash + previous chain hash). Inserts serialised per user via `SELECT FOR UPDATE` on latest record. Tamper detection by re-walking the chain.

**Row Level Security:** Three-layer defence. Layer 1: application always passes `userId` in service signatures. Layer 2: repository WHERE clauses filter by `userId`. Layer 3: PostgreSQL RLS policy rejects any query violating isolation regardless of application code. Session variable `app.current_user_id` set by Hibernate interceptor before every query.

**Outbox pattern:** Raw bank record and job queue entry written in a single transaction before any processing begins. Normalisation worker picks up jobs via `FOR UPDATE SKIP LOCKED`. If normalisation fails, raw data is already safe in the outbox.

**Duplicate detection:** Scoring function with configurable threshold (default 0.85). Weights: amount match 0.40, merchant name fuzzy similarity 0.30, date proximity 0.20, source diversity 0.10, pending/posted pair bonus 0.15. `PROBABLE_PENDING_SETTLEMENT` typed specifically for Basiq's PENDING→POSTED pattern.

**Strategy pattern:** `PredictionEngine` injects all `PredictionStrategy` beans ordered by `@Order`. Each strategy implements `canHandle(PredictionContext)` — engine picks most capable strategy that has sufficient data. `strategyName` and `strategyVersion` recorded on every prediction result. Strategies are never modified — new versions are new classes.

**AOP materialized view refresh:** `@RefreshMaterializedViews` annotation on implementation methods (not interface). Aspect fires `AFTER_RETURNING` and calls `REFRESH MATERIALIZED VIEW CONCURRENTLY`. Option C (domain events via `ApplicationEventPublisher`) is the defined evolution path — additive change when ready.

**Job queue:** PostgreSQL table with `FOR UPDATE SKIP LOCKED` for concurrent-safe polling. Partial unique index prevents duplicate active sync jobs per user at DB layer. Evolution path to Redis Streams is a contained infrastructure swap.

**Partition lifecycle:** December job creates next year's partition. January job reclassifies partitions older than `current year − 5` to ARCHIVED in `partition_registry`. Materialized view refresh excludes ARCHIVED partitions via join on registry. No hardcoded year boundaries anywhere in application code.

---

### 7. Data Model

**12 tables, 2 materialized views, 1 partition registry.**

Core tables: `users`, `revoked_tokens`, `sudo_tokens`, `user_profiles`, `access_grants`, `categories`, `expenses` (partitioned by year), `expense_categories`, `expense_idempotency_keys`, `expense_targets`, `bank_accounts`, `raw_bank_transactions`, `duplicate_flags`, `bank_oauth_states`, `ai_suggestions`, `job_queue`, `dead_letter_jobs`, `partition_registry`.

**Key schema decisions:**
- All PKs are application-generated UUIDs
- All monetary values `NUMERIC(12,2)` — never float
- All timestamps `TIMESTAMPTZ` stored in UTC
- Soft deletes via `deleted_at` on all user-facing records
- `expenses` composite PK `(id, expense_date)` required by PostgreSQL partitioning — cascades to `expense_categories` which must carry `expense_date` on its FK. Rule: `expense_id` never travels without `expense_date` anywhere in the system
- `merged_from` and `is_merged` protected by DB trigger — immutable after set
- `raw_bank_transactions` append-only enforced by `BEFORE UPDATE OR DELETE` trigger that raises exception
- `uq_one_cash_per_user` partial unique index — one system cash account per user
- `idx_one_active_sync_per_user` partial unique index — prevents duplicate sync jobs at DB layer
- All active-record indexes are partial indexes with `WHERE deleted_at IS NULL`
- RLS enabled on all tenant-scoped tables
- `mv_monthly_expense_summary` and `mv_merchant_summary` join on `partition_registry` to exclude archived partitions automatically

---

Ready to move to the C4 diagram now.