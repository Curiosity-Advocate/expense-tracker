# ADR-0012 — Distinguish system categories from user categories via `user_id IS NULL`

> **Context:** Expands [overview.md §5](../overview.md#5-the-data-the-system-holds). Driven by: F14, F15, F19, N14.

**Status:** Accepted. Adopted in v1.0.

---

## Context

The `categories` table holds two kinds of category: **system categories** (UNCATEGORISED, GROCERIES, DINING, …) visible to every user, and **user categories** created privately by a single user. The schema must distinguish them so that:

- Every user sees system categories plus their own.
- A user cannot see another user's private categories.
- Uniqueness rules apply differently — system names are globally unique; user names are unique per user.
- System categories cannot be modified or deleted by users.

## Decision

Use `user_id NULL` as the marker for system categories. No separate `is_system` column.

- `user_id IS NULL` → system category, visible to all.
- `user_id NOT NULL` → user category, scoped to that user.

The RLS policy follows naturally:

```sql
CREATE POLICY category_isolation ON categories
USING (user_id IS NULL
    OR user_id = current_setting('app.current_user_id')::uuid);
```

Uniqueness is enforced by **two partial unique indexes**:

```sql
CREATE UNIQUE INDEX uq_system_category_name
ON categories (name)
WHERE user_id IS NULL;

CREATE UNIQUE INDEX uq_user_category_name
ON categories (user_id, name)
WHERE user_id IS NOT NULL;
```

## Consequences

**Positive.**
- One column carries two facts (ownership and system-ness) without redundancy.
- An `is_system = true` row with `user_id NOT NULL` is structurally impossible. No bug can create one.
- The RLS policy is a one-line `OR`, easy to read and audit.
- Partial unique indexes mean "PETROL" can exist as the system category, as user A's category, and as user B's category — exactly the intended semantics.

**Negative.**
- A nullable foreign key is mildly surprising. A new contributor seeing `user_id NULL` may briefly wonder if it is a bug rather than a design.
- ORM mapping of nullable FKs needs care — Hibernate handles it but the field is `UUID` not `User`.

## Alternatives considered

- **`is_system BOOLEAN` column alongside `user_id`.** Rejected — two columns encoding overlapping facts invite inconsistency. An `is_system = true, user_id NOT NULL` row would be a bug waiting to surface.
- **Separate `system_categories` and `user_categories` tables.** Rejected — every query that wants both (e.g. "list visible categories for user X") becomes a UNION. Junction tables (`expense_categories.category_id`) would need a discriminator. Net more complexity than the single-table approach saves.
