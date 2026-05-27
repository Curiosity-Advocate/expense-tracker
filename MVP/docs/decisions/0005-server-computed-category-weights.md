# ADR-0005 — Server computes category weights at write time

> **Context:** Expands [overview.md §5](../overview.md#5-the-data-the-system-holds) and [overview.md §6](../overview.md#6-how-users-interact-with-the-system). Driven by: F26.

**Status:** Accepted. Adopted in v1.0.

---

## Context

An expense can belong to multiple categories. Reporting and prediction need to know how much of the expense counts against each category. The simplest split is even — a $100 expense across 4 categories is $25 per category. More sophisticated weighting (AI-assisted, receipt-derived) is on the v2.0 roadmap.

Two questions arise:

1. **Where is the weight computed?** Client side, server side, or at query time?
2. **Where is the weight stored?** Pre-computed and persisted, or derived on read?

## Decision

- **Server computes the weight at write time.** The client sends only category names. The server splits the expense amount evenly across the supplied categories and writes one row per `(expense_id, expense_date, category_id)` with the computed `weight_amount`.
- **The weight is stored, not recomputed on read.** A $100 expense across 4 categories is persisted as 4 rows of `weight_amount = 25.00`.

## Consequences

**Positive.**
- **The client cannot tamper with the split.** A malicious or buggy client could otherwise send `categoryWeights: {GROCERIES: 99.99, RENT: 0.01}` to make a discretionary expense look like an essential one, corrupting reporting and predictions.
- **Aggregation queries become trivial sums.** `SUM(weight_amount) GROUP BY category_id` is exactly what summary and target queries need. No per-query split logic.
- **The split logic can evolve without rewriting historical data.** If v2.0 introduces AI-weighted splits, historical rows still carry their original (even-split) weights. New rows carry new weights. No re-computation, no migration.

**Negative.**
- Storing the weight is denormalisation — the same fact (this expense is split N ways) is encoded in N rows. Mitigated because `expense_categories` is append-only at the row level and rewritten on every expense update.
- The schema commits to one weight column. If v2.0 needs to carry both an original split and a recomputed split, an additional column is needed.

## Alternatives considered

- **Client-supplied weights.** Rejected for the tamper reason above. Even a trusted first-party client should not be the source of truth for a financially material number.
- **Compute weights at query time.** `SUM(e.amount / category_count) GROUP BY ...` works but couples every summary query to the split logic. Hard to evolve without breaking historical comparability.
- **Allow custom weights via a future API but always validate sum equals amount.** This is the v2.0 direction; v1.0 deliberately ships only the even split.
