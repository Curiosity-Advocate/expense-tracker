# ADR-0017 — Row-level audit trail via DB triggers

> **Context:** Driven by: F36, future delegation work (D1–D3).

**Status:** Accepted. Adopted in v2.0 (S5, V23, 2026-05-29).

---

## Context

[data-model.md §Cross-cutting conventions](../architecture/data-model.md#cross-cutting-conventions) claimed since v1.0 that every table has `created_by` and `modified_by` UUID columns. That claim was aspirational — the columns were never added. The doc lied; the schema didn't enforce.

Two reasons to fix this now in v2.0 rather than later:

1. **Doc accuracy.** Anyone reading data-model.md and trusting it builds the wrong mental model.
2. **Delegation prerequisites.** D1–D3 (planned for v2.0) allow user B to act on user A's data via a grant + sudo token. The forensic question "who actually modified this row, A or B?" cannot be answered without audit columns. Building delegation without audit-tracking would ship a foot-gun.

The design question wasn't "should we add audit columns" but "how do we populate them?"

## Decision

**Add `created_by` and `modified_by` UUID columns** (both NULL, both FK to `users.id`) to every **user-scoped business table**: `users`, `bank_accounts`, `categories`, `expenses` (partitioned — propagates to all partitions), `expense_categories`, `expense_targets`, `target_categories`.

**Populate via DB triggers reading session variables**, not via JPA `@EntityListeners`. Two new shared trigger functions in V23:

- `set_audit_user()` — fires `BEFORE INSERT OR UPDATE`. Reads `app.acting_user_id` first, falls back to `app.current_user_id`, falls back to NULL. On INSERT both columns get the actor; on UPDATE only `modified_by` changes (and preserves `OLD.modified_by` when no actor is set).
- `lock_created_by()` — fires `BEFORE UPDATE`. Mirrors `lock_created_at` from V1 — DB-enforced immutability of `created_by` after insert.

**Forward-compat for D3 delegation.** The trigger's `COALESCE(acting_user_id, current_user_id)` means D3's gateway filter — once shipped — just sets both session variables when delegation is active: `current_user_id = data_owner` (for RLS) and `acting_user_id = delegate` (for audit). No trigger changes needed at that point. For non-delegated flows today, `acting_user_id` is never set; the trigger falls through to `current_user_id` exactly as if it directly read it.

**Tables explicitly excluded:**

- `user_login_failures` — security log; the row IS the audit by design, no separate `_by` columns needed.
- `refresh_tokens` — the V21 `enforce_immutability_except` trigger allows only `revoked_at` and `revoke_reason` on UPDATE. Adding `modified_by` would either require widening that allow-list (weakening the security boundary) or special-casing this trigger. Refresh tokens are a security primitive whose audit value is captured by `revoke_reason` already.
- `expense_idempotency_keys` — system-managed cache; created once, never updated by users.
- `partition_registry`, `job_execution_state` — system-wide infrastructure, no user concept.

## Consequences

**Positive.**

- Single mechanism populates audit columns regardless of write path: JPA, JdbcTemplate, raw SQL — the trigger fires on all of them.
- Pre-auth flows (setup pool) correctly leave `created_by = NULL` because no session variable is set. No special application-side handling needed.
- Forward-compatible with D3: delegation can record actor without any S5 changes.
- Defence in depth — the DB enforces audit population, not just the application. Even raw SQL gets the columns populated.
- Matches the existing V1 trigger pattern for `created_at` / `updated_at` — same pattern, same mental model.

**Negative.**

- **Trigger logic in SQL.** Less inspectable than Java. Mitigation: the trigger is short (~25 lines), commented, and unit-testable via integration tests (`AuditTrailIntegrationTest`).
- **Worker updates (current state: none on user-scoped rows) would erase modified_by if the trigger nulled it.** Mitigation: the trigger preserves `OLD.modified_by` when no actor is set, so the audit trail survives non-user writes.
- **Pre-auth audit columns are NULL.** A user-list view that joins on `created_by` will see NULL for self-registered users. Acceptable — historical "user created themselves" is implicit; admin-created users (a future feature) will populate the column meaningfully.
- **10 tables modified by V23.** Big migration. Each `ALTER TABLE ADD COLUMN` on a NULL-able column is metadata-only in modern Postgres, so apply cost is small. Trigger creation is per-table.

## Alternatives considered

- **JPA `@EntityListeners(AuditingEntityListener.class)` + `AuditorAware<UUID>`.** Rejected — only fires for JPA writes. The setup-pool services (`PostgresAuthService`, `DefaultUserSetupService`, `RefreshTokenChainRevoker`) use `JdbcTemplate` and would bypass the listener entirely. The triggers cover those paths uniformly.
- **Application-only population (every service explicitly sets the columns).** Rejected — every existing service method would need updating; high diff and easy to miss new endpoints. Triggers add zero application code.
- **Separate `audit_log` table capturing every write.** Heavier and out of scope for "who currently owns/modified this row?" Could be added later as a row-history feature without conflicting with this design.
- **Use only `app.current_user_id`, ignore `app.acting_user_id`.** Considered, rejected because it would prevent D3 from correctly recording the actor when delegation is active. The forward-compat COALESCE adds one line for substantial future value.
- **Include `refresh_tokens` in the audit-column scope.** Rejected — the V21 immutability trigger conflict would require either weakening V21 or per-table trigger gymnastics. Refresh tokens have their own audit story (`revoke_reason`) which is more informative for that table's purpose.

## Operational notes

- **Migration:** [V23__add_audit_user_columns.sql](../../adapters/src/main/resources/db/migration/V23__add_audit_user_columns.sql)
- **Trigger functions:** `set_audit_user` and `lock_created_by`, both reusable for future tables — same pattern as `set_updated_at` and `lock_created_at` from V1.
- **Session variables:**
  - `app.current_user_id` — set by `RlsSessionAspect` on every app-pool `@Transactional` method. Drives RLS.
  - `app.acting_user_id` — never set today. Will be set by D3's gateway filter when delegation is active.
- **Forensic queries** become trivial once D3 ships. Example: "every expense user B touched, including ones owned by other users via delegation" → `SELECT * FROM expenses WHERE modified_by = :b_id` — no JOIN, no aggregation, single index scan if we ever add `(modified_by)` indexes.
- **Integration tests:** `api/src/test/java/com/finance/integration/AuditTrailIntegrationTest.java` covers app-pool happy path, setup-pool NULL, `lock_created_by` enforcement, and the D3 forward-compat delegation simulation.
