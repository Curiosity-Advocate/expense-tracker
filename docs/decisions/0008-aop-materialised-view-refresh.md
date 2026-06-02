# ADR-0008 — AOP for materialised view refresh

> **Context:** Expands [overview.md §5](../overview.md#5-the-data-the-system-holds). Driven by: F37, N17, N18.

**Status:** Accepted in design. Aspect implementation deferred — current code calls refresh from the worker on a schedule only.

---

## Context

Summary endpoints (`GET /api/v1/expenses/summary`, target status) read from materialised views, not raw tables (N18). Aggregation across thousands of rows is too slow to compute on every request.

Materialised views must be refreshed after writes to keep their content reasonably current. The naive solution is to call `REFRESH MATERIALIZED VIEW` inside every service method that mutates expenses. This duplicates the call across many places and creates a silent-failure risk: a new method that mutates expenses but forgets the refresh leaves the views stale and the bug invisible.

## Decision

Apply the refresh **declaratively via an aspect**.

- A `@RefreshMaterialisedView` annotation marks service implementation methods that mutate aggregated data.
- An aspect intercepts `AFTER_RETURNING` on any method bearing the annotation and runs `REFRESH MATERIALIZED VIEW CONCURRENTLY` on the affected views.
- Service interfaces are not annotated — only implementations, because the aspect is an implementation concern.

This forces two design consequences elsewhere:

- Each materialised view must have a **unique index** for `REFRESH ... CONCURRENTLY` to succeed (see [data-model.md](../architecture/data-model.md)).
- The materialised views are wrapped in regular views that apply the RLS session-variable filter, because materialised views ignore RLS policies (see [ADR-0014](0014-materialised-view-wrapper-for-rls.md)).

The defined evolution path is **Option C — domain events via `ApplicationEventPublisher`**. When the system reaches a scale where refreshing after every write is wasteful, the aspect publishes events, a debouncing listener batches them, and the worker drains the batch on a schedule. This is an additive change — services and the annotation stay the same.

## Consequences

**Positive.**
- Services declare intent through the annotation and never call refresh explicitly.
- Adding a new mutation method is one annotation away from correct refresh behaviour.
- A forgotten annotation is a single code-review check, not a hunt for missing refresh calls.

**Negative.**
- `REFRESH ... CONCURRENTLY` is not free. At MVP scale this is fine. Under load v2.0 must move to scheduled refresh.
- AOP introduces an indirection that newcomers must learn before they can trace why an INSERT also fired a REFRESH.

## Alternatives considered

- **Call refresh inline at every mutation site.** Rejected — every new mutation is a new chance to forget; the bug is silent and invisible.
- **DB triggers fire the refresh.** Rejected because `REFRESH MATERIALIZED VIEW` cannot be run inside a transaction that wrote to the underlying table. It must run after commit, which is exactly what AOP `AFTER_RETURNING` provides.
- **Pre-aggregated regular tables with manual maintenance.** More machinery, more places to introduce drift between raw and aggregated. The materialised view + `CONCURRENTLY` story is purpose-built for this.
