# ADR-0014 — Wrapper regular view over materialised view for RLS

> **Context:** Expands [overview.md §5](../overview.md#5-the-data-the-system-holds). Detail in [architecture/data-model.md](../architecture/data-model.md). Driven by: N1, N2, N17, N18.

**Status:** Accepted. Adopted in v1.0.

---

## Context

PostgreSQL Row Level Security policies apply to base tables but **not to materialised views**. The materialised view is treated like a regular SELECT result — the policy on the underlying tables has already run at refresh time, but reads of the materialised view itself are unfiltered.

The system relies on RLS as the third defence layer (see [ADR-0011](0011-three-layer-rls-defence.md)). If the application queries a materialised view directly, that defence layer is missing — a bug in the repository's `WHERE` clause would return another user's aggregated rows.

## Decision

For every materialised view, also create a **regular view** that filters by the session variable, and have the application always query the wrapper:

```sql
CREATE MATERIALIZED VIEW mv_monthly_expense_summary AS
SELECT user_id, period_year, period_month, category_id,
       SUM(weight_amount) AS total_amount, COUNT(*) AS transaction_count
FROM expense_categories ec JOIN expenses e ON ...
WHERE e.deleted_at IS NULL
GROUP BY user_id, period_year, period_month, category_id;

CREATE VIEW v_monthly_expense_summary AS
SELECT * FROM mv_monthly_expense_summary
WHERE user_id = current_setting('app.current_user_id')::uuid;
```

The application queries `v_monthly_expense_summary`. The materialised view (`mv_…`) is an implementation detail.

## Consequences

**Positive.**
- The same three-layer defence applies to materialised views as to base tables. A repository bug cannot leak cross-user data.
- The pattern is uniform — every materialised view has a `v_…` wrapper. No special-casing in the service layer.
- The wrapper carries zero cost when the session variable is set correctly — PostgreSQL inlines the filter into the query plan.

**Negative.**
- One extra DDL object per materialised view (currently two).
- A reader unfamiliar with the pattern may briefly wonder why both objects exist. A short comment on the view definition makes the intent obvious.

## Alternatives considered

- **Apply RLS in repository code.** Rejected — defeats the defence-in-depth model. We have the session variable already; the wrapper view consumes it the same way base-table RLS does.
- **Embed the user filter directly in the materialised view's SELECT.** Cannot work — the materialised view is shared across all users, and the session variable is per-transaction.
- **Refresh per-user materialised views.** Refreshing N views per user per write is infeasible. Single shared materialised view + per-user wrapper view is the standard pattern for this constraint.
