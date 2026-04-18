### Functional Requirements

**FR-001 — Expense Management**

- User can create an expense with mandatory fields: amount (AUD), merchant name, date
- Optional fields: category (multi-select), notes, payment method, bank account reference
- Currency is always AUD; no multi-currency support in V1
- One expense can belong to multiple categories; budget impact is divided evenly across assigned
  categories (e.g. $100 across 4 categories = $25 per category)
- Future: AI-assisted category weighting from attached receipts (out of scope V1)

**FR-002 — Expense Querying**

- Filter by: date range, merchant, category, payment method, bank account, amount threshold
- Group by: category, merchant, bank account, month
- All filters combinable

**FR-003 — Expense Targets**

- User can set a spending target per category per month, and a total monthly target
- System reports: dollar amount spent, dollar amount remaining, percentage used
- System predicts whether target will be exceeded by end of month (using current spend rate × days
  remaining), reported in dollar amount

**FR-004 — Bank Integration**

- Bank sync is manually triggered by the authenticated user only
- Raw bank data is stored in an append-only, tamper-evident store using hash chaining
- System detects potential duplicate transactions (manual vs bank-imported) and flags them
- V1: duplicates are flagged but preserved; merge is a user action
- Future: configurable auto-merge with audit trail; merged records are permanently tagged as merged
  (immutable field, enforced at DB layer)

**FR-005 — AI Categorisation**

- AI suggests categories for uncategorised transactions
- V1: suggestions presented to user for confirmation before committing
- Future: configurable auto-apply with override capability

**FR-006 — User Management & Access Control**

- Multi-user, strict data isolation enforced at DB layer via Row Level Security
- User must opt-in to being "discoverable" before another user can grant them access
- Access grants are scoped (read-only vs full), with expiry between 1 and 10 days (default: 1 day)
- Authentication: username/password with BCrypt hashing
- Future: Google OAuth, TOTP-based MFA

**FR-007 — Observability & Failure Handling**

- Failed bank sync jobs written to dead letter table with full context
- Dead letter records surfaced via a dedicated API endpoint
- Manual re-trigger of failed syncs available via API
- V1: no active alerting; future: push alerts to mobile

---

### Non-Functional Requirements

**NFR-001 — Performance**

- Sustained read throughput: 5,000 req/min
- Peak burst: 15,000 req/min (handled via read replica)
- Dashboard query P95 latency: < 800ms
- Analytical/prediction query P95 latency: < 2s
- Write latency P95: < 300ms (strong consistency enforced)

**NFR-002 — Data Consistency**

- Write path: strong consistency (ACID, synchronous commit)
- Read path: bounded eventual consistency, maximum staleness of 60 seconds
- Materialized views refreshed on-write at low volume; scheduled at 60s intervals under load

**NFR-003 — Availability**

- MVP: high downtime tolerance (home server, manual WoL)
- V1 upgrade: WoL-enabled home server
- Target future state: 99.9% uptime (three nines) on cloud deployment

**NFR-004 — Reliability — Bank Sync**

- Retry strategy: exponential backoff (1m → 2m → 4m → ... capped at 2h) for up to 10 attempts per
  cycle
- 3 cycles with 24h cooling-off between cycles
- After 3 failed cycles: dead letter entry created, manual re-trigger required

**NFR-005 — Data Retention**

- Records are never deleted
- Expenses older than 5 years are archived (moved to cold storage partition)
- Archived records remain queryable but are not included in active materialized views

**NFR-006 — Security**

- Row Level Security enforced at PostgreSQL layer
- Raw bank ingestion data: append-only with hash chaining for tamper evidence
- Audit fields on merge records: immutable after insert, enforced by DB trigger
- Session tokens: long-lived in V1 (configurable, suggest 7 days)
- Future: short-lived access tokens (15min) with refresh tokens, TOTP MFA

**NFR-007 — Observability**

- V1: structured logging (JSON) to file with rotation; manual log inspection
- Dead letter table as primary failure visibility mechanism
- Future: metrics dashboard, push alerts to mobile
