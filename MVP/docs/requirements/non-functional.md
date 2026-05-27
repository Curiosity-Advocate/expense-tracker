# Non-Functional Requirements

> **Context:** Expands [overview.md §2](../overview.md#2-how-the-problem-was-carved-up). The IDs below (N1–N22) are cited throughout [architecture/data-model.md](../architecture/data-model.md), [architecture/api-contract.md](../architecture/api-contract.md), and the [decisions/](../decisions/) folder.

Qualities the system must hold while delivering the functional requirements. Each ID is referenced from the design decisions and schema artefacts that satisfy it.

---

## Security

| ID | Requirement |
|---|---|
| N1 | Data isolation enforced at three layers — application, aspect, and database |
| N2 | RLS policies on every table containing user-scoped data |
| N3 | HTTPS enforced via redirect and HSTS |
| N4 | Passwords BCrypt hashed before storage |
| N5 | JWT signing with rotation capability |
| N6 | Bank credentials never stored in DB — Bitwarden reference only — deferred to v2.0 |
| N7 | Hash chaining for tamper evidence on bank imported data — deferred to v2.0 |

---

## Data integrity

| ID | Requirement |
|---|---|
| N8 | Bank imported expenses are immutable — deferred to v2.0 |
| N9 | Manual expenses are editable and soft deletable |
| N10 | No physical deletion — soft delete only across all entities |
| N11 | Expense table partitioned by year — composite primary key of `(id, expense_date)` |
| N12 | Active partitions cover 5 years, older partitions archived to cold storage |
| N13 | Username and email globally unique |
| N14 | Category names unique per user |
| N15 | Targets cannot be created for periods that have already ended |

---

## Performance

| ID | Requirement |
|---|---|
| N16 | Scale assumption — 10 users, 100 writes per month and 500 reads per month per user |
| N17 | Materialised views with concurrent refresh — requires unique index on view |
| N18 | Summary queries hit materialised views, not raw tables |

---

## Operability

| ID | Requirement |
|---|---|
| N19 | Structured JSON logs with rotation |
| N20 | Every request carries a `traceId` for correlation |
| N21 | Failures captured in logs sufficient for diagnosis |
| N22 | Cron jobs are idempotent — re-running has no side effect |
