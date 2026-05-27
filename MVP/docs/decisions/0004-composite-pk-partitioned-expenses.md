# ADR-0004 — Composite primary key on partitioned `expenses`

> **Context:** Expands [overview.md §5](../overview.md#5-the-data-the-system-holds). Detail in [architecture/data-model.md](../architecture/data-model.md). Driven by: N11.

**Status:** Accepted. Adopted in v1.0.

---

## Context

The `expenses` table is partitioned by year on `expense_date` for retention management — five active years, older years archived to cold storage (see [ADR-0013](0013-yearly-expense-partitions.md)).

PostgreSQL has a rule: **every unique constraint on a partitioned table must include the partition key**. A primary key is a unique constraint. So the PK on `expenses` cannot be a simple `id UUID`; it must include `expense_date`.

## Decision

Use a **composite primary key `(id, expense_date)`** on `expenses`. Every table that references an expense must carry both columns and use a composite foreign key.

The rule across the schema: **`expense_id` never travels without `expense_date`**.

Concrete consequences:

- `expense_categories.PRIMARY KEY = (expense_id, expense_date, category_id)` with `FOREIGN KEY (expense_id, expense_date) REFERENCES expenses(id, expense_date)`.
- `expense_idempotency_keys` carries `expense_id` and `expense_date` together.
- API single-expense endpoints (`GET /api/v1/expenses/{id}`, `PATCH`, `DELETE`) require `expenseDate` as a query parameter for the lookup.

## Consequences

**Positive.**
- Partitioning works without sacrificing relational integrity.
- Lookups by `(id, expense_date)` are partition-pruned — PostgreSQL only scans the correct yearly partition.
- The rule is consistent everywhere — there is exactly one shape for "reference an expense".

**Negative.**
- The API is slightly less idiomatic. `GET /expenses/{id}` would normally need only the id; here it also needs the date. Clients must remember to send it.
- Hibernate `@EmbeddedId` boilerplate — every entity referencing an expense needs its own `*Id` class.
- An expense's date cannot be changed via UPDATE. Changing the date would move the row to a different partition, which is not a simple UPDATE. Updates to `expenseDate` are blocked at the command layer.

## Alternatives considered

- **Surrogate global UUID without partitioning.** Rejected — yearly partitioning is required by N12 for archival.
- **Partition on `EXTRACT(YEAR FROM expense_date)` as a derived column.** Would still require the column in the PK; net no benefit.
- **`id` as PK without partitioning, archive via background move.** Considered, but moving 5+ years of data periodically is far more operationally fragile than declarative partitioning.
