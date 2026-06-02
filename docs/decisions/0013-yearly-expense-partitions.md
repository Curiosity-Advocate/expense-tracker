# ADR-0013 — Yearly partitions for `expenses` with a partition registry

> **Context:** Expands [overview.md §5](../overview.md#5-the-data-the-system-holds). Detail in [architecture/data-model.md](../architecture/data-model.md). Driven by: F34, F35, N11, N12.

**Status:** Accepted. Adopted in v1.0.

---

## Context

Expense records accumulate forever — soft delete only, no physical removal (see [ADR-0003](0003-soft-delete-only.md)). Indefinite growth on a single table eventually slows queries and inflates indexes, even though most operational reads only ever touch the last 12–24 months.

Five years of active data is the sensible operational window for personal finance — long enough for year-over-year trend comparisons, short enough to keep working set small. Older data should remain queryable but should not weigh down everyday queries.

## Decision

Adopt **declarative range partitioning by `expense_date`, one partition per calendar year**.

- `expenses` is partitioned by `RANGE (expense_date)`.
- Five partitions are active at any time (current year + four prior years).
- A `partition_registry` table tracks `(partition_year, status)` with status `ACTIVE` or `ARCHIVED`.
- Materialised views join `partition_registry WHERE status = 'ACTIVE'` so archived data is excluded from aggregates automatically.

**Lifecycle**:

- A daily December cron job creates next year's partition idempotently (if not already present).
- A daily January cron job reclassifies the partition older than `current_year - 5` from `ACTIVE` to `ARCHIVED` and archives the partition to cold storage. Daily idempotency means a one-day failure self-recovers on the next run.

Partition naming is **deterministic** — `expenses_<year>`. No name column needed.

## Consequences

**Positive.**
- Partition pruning — PostgreSQL only scans the partition matching the date predicate on every query. Query plans stay flat as data grows.
- Index size is bounded per year.
- Archival is a metadata flip (status change), not a delete or a data move. Cheap and reversible.
- The "active vs archived" question is asked once per refresh of the materialised view, not on every query.

**Negative.**
- Every unique constraint on `expenses` must include `expense_date`. This is what forces the composite PK (see [ADR-0004](0004-composite-pk-partitioned-expenses.md)).
- A bug in the December cron means inserts in January fail with "no partition for value". Mitigated by the cron running daily through December — eleven retries before insert traffic hits the new year.
- An `expense_date` cannot be changed via UPDATE if it would move the row to a different partition. Edits to `expenseDate` are blocked at the command layer.

## Alternatives considered

- **Single non-partitioned table with periodic archival to a separate table.** Considered. Moving five years of data periodically is more operationally fragile than declarative partitioning, and the queries still scan the full table until the move happens.
- **Partition by month.** Too granular — 60 partitions for five years, more cron complexity for no win at MVP scale.
- **`pg_partman` extension for automated partition management.** Useful at scale; overkill for MVP. The two simple cron jobs are easier to read and debug.
