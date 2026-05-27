# ADR-0015 — Domain objects are point-in-time snapshots, not live references

> **Context:** Expands [overview.md §4](../overview.md#4-the-shape-of-the-system). Driven by: N1, N9.

**Status:** Accepted. Observed during implementation; documented as a constraint for future service growth.

---

## Context

Domain records — `UserProfile`, `Expense`, `Target`, and others — are immutable records returned from service methods. Each one is a snapshot of database state at the moment it was fetched. They are not connected to a session, not live, not refreshable.

This is fine for the current MVP because every service method does at most one read and one write, then returns. The pattern becomes a footgun the moment a service method grows to hold two snapshots of the same entity at different times within a single logical operation: both objects claim to represent the same row, but one reflects state from before an update and the other from after, and any field that changed between the two reads will be inconsistent.

## Decision

Make the constraint explicit: **domain objects are point-in-time snapshots, not live references**. Within a single logical operation, do not hold two snapshots of the same entity taken at different times.

Three safe patterns satisfy the rule:

1. **Read once at the start of the operation** and pass the single snapshot through. Do not re-fetch mid-operation unless the intent is explicitly to refresh.
2. **Wrap the entire logical operation in one `@Transactional` boundary.** All reads inside the transaction see the same consistent DB snapshot under PostgreSQL's default Read Committed isolation.
3. **Where concurrent writes are possible and correctness depends on detecting them**, use optimistic locking (`@Version` on the entity). A stale read that tries to write fails with `OptimisticLockException` and retries rather than silently corrupting data.

## Consequences

**Positive.**
- The constraint is stated, so future contributors know to apply one of the three patterns instead of stumbling into the bug.
- The pattern aligns with the domain-as-data-carrier model. Domain objects stay immutable records, not entity-manager-attached objects that magically refresh.

**Negative.**
- Multi-read service methods need conscious choice between the three patterns. The compiler does not force it.
- Optimistic locking introduces retry logic at the call sites that opt in.

## Why this is not yet a runtime concern in v1.0

No service method in the current codebase holds multiple snapshots of the same entity within a single logical operation. Each controller calls one service method, which does one read, one optional write, and returns. The constraint becomes a real design pressure only when service methods grow to orchestrate multiple reads and writes over a single request — which is the natural direction as complexity increases.

This ADR exists to make the constraint visible before that growth happens.
