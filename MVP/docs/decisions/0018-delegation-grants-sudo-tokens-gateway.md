# ADR-0018 — Delegation via grants, sudo tokens, and gateway substitution

> **Context:** Covers v2.0 items D1, D2, D3 — the three-piece delegation mechanism — as a single coherent feature. Driven by: F7–F13, N10. Builds on [ADR-0011](0011-three-layer-rls-defence.md) (three-layer RLS) and [ADR-0017](0017-row-level-audit-trail.md) (audit columns).

**Status:** Accepted. Adopted in v2.0 (D1 + D2 + D3, V24 + V25, 2026-05-31).

---

## Context

The system serves a small group of Australian households — the developer plus close friends and relatives. Within that group, technical comfort varies. Occasionally a more-technical user needs to step in and correct a less-technical user's data (e.g., fix a miscategorised expense). v1.0 had no mechanism for this; v2.0 needed one.

The constraints:

- **No persistent role escalation.** Delegation is per-request, not a permanent "B can edit A's stuff forever" mode. Every action under delegation is bounded in time and scope.
- **Audit trail must record both parties.** "Who actually did this?" needs a clear answer — not just "user A's data" but "user B modified user A's expense via grant X." Forensic value collapses if the audit only shows the data owner.
- **No new permanent privilege in the auth model.** The mechanism shouldn't grant standing admin-like privileges that could be exploited if a user's account is compromised.
- **Scope-limited.** Auth, profile, access-grant management, and any future admin operations must NEVER be delegated. Only expense-related operations are in scope.

## Decision

**Three pieces, each independently useful, composing into delegation:**

### D1 — `access_grants`

Persists "user A allows user B to act on A's data until expires_at" as a row in a new `access_grants` table. CRUD API under `/api/v1/users/me/access-grants`. Grants are 1–30 days, soft-revocable by either party, and require the grantee to have `is_discoverable = TRUE` (opt-in to receive grants). Grants exist as records and can be created/listed/revoked at runtime, but **they do not by themselves enable any cross-user data access** — they're just authorisation records.

### D2 — `sudo_tokens`

Short-lived (15-min) opaque tokens minted by the grantee via password re-entry. SHA-256-hashed at rest (same pattern as refresh tokens). Each sudo token references one D1 grant via `grant_id` FK. Verification joins `access_grants` so revoking the grant immediately invalidates all its sudo tokens with no cascade UPDATE needed.

### D3 — `AsUserIdFilter`

A Spring Security filter running after `JwtAuthenticationFilter`. Detects `?asUserId=<grantor>` query parameter + `X-Sudo-Token` header, verifies the token against the active grant, and substitutes the `SecurityContext` principal:

```
Before substitution:  UserPrincipal(userId = B, username = B, actingAs = null)
After substitution:   UserPrincipal(userId = A, username = B, actingAs = B)
```

`RlsSessionAspect` reads the substituted principal and sets two session variables:
- `app.current_user_id = A` — drives RLS, so the request sees A's data
- `app.acting_user_id = B` — read by [S5's `set_audit_user` trigger](0017-row-level-audit-trail.md) via `COALESCE`, so audit columns record B

The S5 trigger's COALESCE design is the forward-compat hook that made D3 a clean addition rather than a schema migration.

### The session-variable mechanism

```
┌──────────────────────────────────────────────────────────────────────┐
│  Request: GET /api/v1/expenses?asUserId=A                            │
│  Headers: Authorization: Bearer <B's JWT>, X-Sudo-Token: <raw>       │
└──────────────────────────────────────────────────────────────────────┘
              │
              ▼
      JwtAuthenticationFilter
        sets SecurityContext.principal = UserPrincipal(B, B, null)
              │
              ▼
      AsUserIdFilter
        verifies sudo token + grant via SudoTokenService.verify
        swaps principal to UserPrincipal(A, B, B)
              │
              ▼
      Controller method runs with @AuthenticationPrincipal = A
              │
              ▼
      @Transactional opens
        RlsSessionAspect fires:
          SET LOCAL app.current_user_id = A   (RLS sees A's rows)
          SET LOCAL app.acting_user_id  = B   (audit triggers record B)
              │
              ▼
      Service executes:
        - SELECT/INSERT scoped to A by RLS
        - INSERT/UPDATE rows have created_by/modified_by = B (S5 trigger COALESCE)
```

### Scope restriction

`AsUserIdFilter` maintains an allow-list of URL prefixes where delegation is permitted. v2.0 ships with `/api/v1/expenses` only. Requests with `?asUserId=` on any other endpoint return `403 ASUSER_NOT_ALLOWED_HERE`.

Allow-list (not deny-list) is the explicit policy choice. Adding `/api/v1/categories` or `/api/v1/targets` to the list is a deliberate future decision; defaulting endpoints to "delegatable" would be the wrong direction.

### Enumeration defence

All three pieces collapse multiple failure conditions into single opaque error codes:

- D1's `GranteeNotDiscoverableException` collapses "unknown username" and "is_discoverable = FALSE" into one 404
- D1's `GrantNotFoundException` collapses "no such grant" and "you're not party to this grant" (RLS-hidden) into one 404
- D2's `GrantNotUsableException` collapses four conditions (unknown grant / not yours / revoked / expired) into one 401
- D2's `InvalidSudoTokenException` collapses every verify failure into one 401
- D3's filter returns the same `INVALID_SUDO_TOKEN` 401 for missing token, invalid token, and grantor-mismatch

Same rationale as [`InvalidCredentialsException`](../../core/src/main/java/com/finance/exception/InvalidCredentialsException.java) at login.

## Consequences

**Positive.**

- Delegation works end to end without weakening the existing three-layer RLS defence. Every authenticated path still passes through `RlsSessionAspect` → DB RLS check.
- Audit columns answer "who did this?" without log mining. `SELECT * FROM expenses WHERE modified_by = B` returns every row B touched, including ones owned by other users via delegation.
- The three pieces are independently testable. D1 + D2 ship as inert records before D3 activates them; the integration tests for each piece don't depend on the others.
- Forward-compat with future delegation evolution. Adding a second session variable (e.g., `app.acting_via_grant_id` for the v3.0 stretch goal — see [roadmap.md](../roadmap.md)) doesn't require revisiting the session-variable architecture — just one more `SET LOCAL` in the aspect.

**Negative.**

- **`principal.userId()` and `principal.username()` refer to different people in delegated requests.** `userId` is the grantor (for RLS); `username` is the grantee (from the JWT, used for display). Documented in `UserPrincipal` javadoc. Rarely a problem in practice — most callers use `userId`.
- **`AsUserIdFilter` does a DB round-trip on every request that has `?asUserId=`.** Sudo token verification is the security check; can't be skipped. Mitigation: the vast majority of requests are non-delegated and pay nothing.
- **Log enrichment for delegation is deferred to v3.0.** Without it, structured log lines emitted by downstream services attribute actions to the grantor (the substituted `userId`) rather than the actual requestor. Operators have to correlate B's JWT trace with A's audit-column appearances. See the v3.0 delegation-enhancements section in roadmap.md.
- **Refresh tokens are not delegation-aware.** A refresh from B works normally and doesn't know about any active delegations. This is fine — refresh just rotates the JWT identity; delegation is per-request via the filter, not part of the JWT.

## Alternatives considered

- **Header-based `asUserId` instead of query param.** Rejected. Query parameters appear in URL logs and `request.url` strings; headers don't. Making "I'm acting as someone else" visible in every log line is a feature, not a bug — operators reviewing audit trails should see immediately when delegation was used.
- **Custom JWT claim with `acting_as_user`.** Rejected. Would require minting a delegation-specific JWT in addition to the normal access token, complicating the token model. Also makes "this token can act on multiple users' behalf" a property of the token rather than the request, which is harder to revoke instantly.
- **Separate `DelegationService` with its own auth flow.** Rejected as over-engineering. The three-piece split (grant + sudo token + filter) already separates the lifecycle concerns; adding a fourth abstraction layer doesn't pay for itself.
- **Per-row delegation flag on `expenses`.** Considered: `expenses.delegated_modified BOOLEAN`. Rejected because audit columns (S5) plus the grant_id-stretch (v3.0) cover the forensic question more flexibly. Per-row flag also doesn't compose well with future "view this row from delegation's perspective" features.
- **No scope restriction (delegation works everywhere).** Rejected. Auth endpoints under delegation would be catastrophic (B could mint a sudo token while acting as A, then act as anyone). Profile updates under delegation would let a delegate change A's email and reset A's password. Allow-list is the only safe default.

## Operational notes

- **Migrations:**
  - [V24__create_access_grants.sql](../../adapters/src/main/resources/db/migration/V24__create_access_grants.sql) (D1)
  - [V25__create_sudo_tokens.sql](../../adapters/src/main/resources/db/migration/V25__create_sudo_tokens.sql) (D2)
- **Filter chain order:** `TraceIdFilter → JwtAuthenticationFilter → AsUserIdFilter → controller`.
- **Endpoints introduced:**
  - `POST /api/v1/users/me/access-grants`, `GET /api/v1/users/me/access-grants`, `DELETE /api/v1/users/me/access-grants/{id}` (D1)
  - `POST /api/v1/auth/sudo-tokens` (D2)
- **Session variables (set by `RlsSessionAspect`):** `app.current_user_id` (always), `app.acting_user_id` (only when delegation is active).
- **Integration tests:**
  - `AccessGrantIntegrationTest` — D1
  - `SudoTokenIntegrationTest` — D2 (create + verify)
  - `DelegationIntegrationTest` — D3 end-to-end via HTTP; centrepiece test proves the full D1→D2→D3→S5 chain
- **Deferred to v3.0** (see [roadmap.md](../roadmap.md)):
  - Log enrichment for delegated requests (MDC `delegated_by` field)
  - `modified_via_grant_id` column on user-scoped tables for at-a-glance delegation-context visibility
