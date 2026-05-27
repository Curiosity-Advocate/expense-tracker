# ADR-0003 — Soft delete only across all entities

> **Context:** Expands [overview.md §5](../overview.md#5-the-data-the-system-holds). Driven by: N10, N12.

**Status:** Accepted. Adopted in v1.0.

---

## Context

Financial data has long-term value beyond the moment a user wants to remove it. A deleted expense may still need to appear in last year's summary. A deleted target may inform future trend analysis. A deleted bank account may still be referenced by historical expenses.

Hard deletion makes some questions unanswerable later. It also creates referential integrity problems — every foreign key to a soft-deletable entity becomes a danger zone.

## Decision

**No physical deletion of any user-facing entity.** Every such table carries `deleted_at TIMESTAMPTZ NULL`. NULL means active. A non-NULL value marks the record as logically deleted.

- All list and summary endpoints filter out `deleted_at IS NOT NULL` by default.
- All active-record indexes are partial indexes with `WHERE deleted_at IS NULL` to keep them lean.
- Cleanup jobs delete only **operational** records — expired revoked tokens, expired idempotency keys, expired access grants — which have no historical value.

## Consequences

**Positive.**
- Historical queries remain correct after a user "deletes" something.
- Referential integrity is preserved without cascading deletes.
- Audit and compliance scenarios become trivial — the record is still there.
- An accidental delete is recoverable by clearing the `deleted_at` field.

**Negative.**
- Every query must remember to filter `deleted_at IS NULL`. A missed filter exposes deleted rows. Mitigated by partial indexes and code review.
- The DB grows unbounded over time. At MVP scale (N16: 10 users, 100 writes/user/month) this is not a problem for a decade. Re-evaluate before that.
- A "delete forever" feature (legal right-to-erasure under GDPR/PIPEDA) becomes a separate, auditable workflow rather than a normal delete.

## Alternatives considered

- **Hard delete with cascade.** Rejected — destroys historical data and creates referential nightmares.
- **Hard delete with archival to a separate table.** More machinery for no clear gain. The same can be achieved with soft delete + the existing partition archival flow.
- **Different rules per entity (some hard, some soft).** Rejected because consistency across the schema is itself a feature — there is exactly one rule to remember.
