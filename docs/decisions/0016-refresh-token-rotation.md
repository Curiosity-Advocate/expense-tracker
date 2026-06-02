# ADR-0016 — Refresh-token rotation with reuse detection and max-session cap

> **Context:** Expands [overview.md §6](../overview.md#6-how-users-interact-with-the-system). Supersedes [ADR-0009](0009-jwt-revocation-via-jti-table.md). Driven by: F3, N5.

**Status:** Accepted. Adopted in v2.0 (S4, V21 + V22, 2026-05-29).

---

## Context

v1.0 issued a single 7-day JWT access token and tracked revocations in `revoked_tokens`. Two problems with that design:

1. **Stolen-token window is 7 days.** If an access token is leaked — through XSS, a browser-extension bug, a shared machine, or a log scrape — the attacker has up to a week of authenticated access. Logout via the JTI table works only if the user notices and acts, which rarely happens.
2. **Per-request DB lookup.** Every authenticated request hits `revoked_tokens` to check whether the JWT's `jti` has been revoked. Negligible at MVP scale but unbounded as the table grows between cleanup runs, and conceptually wrong — a stateless JWT shouldn't require a DB check on every use.

The goal was: shorten the stolen-token window without forcing users to re-authenticate every 15 minutes.

## Decision

**Two token types with different jobs:**

- **Access token** — JWT, 15-minute expiry, stateless. Sent on every authenticated request. Validated by signature + expiry only — no DB lookup. The filter (`JwtAuthenticationFilter`) is one HMAC verify per request.
- **Refresh token** — opaque random 32-byte value (256 bits of entropy), 7-day expiry from original login. Sent only to `/api/v1/auth/refresh` and `/api/v1/auth/logout`. SHA-256 hash stored in `refresh_tokens`; raw token never persisted.

**Rotation on every refresh.** Each `/refresh` call:
1. Marks the presented token as revoked (`revoke_reason = 'ROTATED'`)
2. Issues a fresh access + refresh pair
3. Inserts a new `refresh_tokens` row with `rotated_from = <previous hash>` and `session_started_at` **copied unchanged** from the parent row

**Max-session cap.** A refresh-token chain inherits its predecessor's `session_started_at`. The chain's `expires_at = session_started_at + refresh-token-expiry-days`. Rotation cannot extend it. A user must re-authenticate with password after 7 days of original-login wall-clock time, regardless of how many rotations occurred.

**Reuse detection with cascade revocation.** Presenting an already-rotated refresh token at `/refresh` is treated as compromise:
- All of the user's currently-active refresh tokens (across every parallel chain) are revoked with `revoke_reason = 'REUSE_DETECTED'`
- The cascade runs in a `REQUIRES_NEW` transaction (via `RefreshTokenChainRevoker`) so it commits independently of the failing refresh's transaction
- `RefreshTokenReuseException` is thrown, forcing full re-login on every device

**Race-safe atomicity.** Refresh uses a conditional UPDATE — `WHERE token_hash = :hash AND revoked_at IS NULL` — as both check and revoke. `rowcount = 1` means we won the rotation race. `rowcount = 0` means another transaction beat us; we treat the loser as reuse. No `SELECT FOR UPDATE` needed. Postgres' MVCC + the conditional WHERE handle correctness.

**Append-only enforcement at the DB.** Two triggers from V21:
- `enforce_immutability_except('revoked_at', 'revoke_reason')` — JSONB diff catches any column drift, including columns added by future migrations (fail-closed)
- `enforce_set_once_column('revoked_at')` — even within allowed columns, revocation is one-way

**Two pools.** Refresh and logout run on the setup pool because the token-hash lookup happens before any `UserPrincipal` exists. The chicken-and-egg of "find the row to know the user, but RLS needs the user first" forces this. The setup pool's bypass mechanism (BYPASSRLS in the v2.0 design; owner-bypass under the Option-A pivot for managed Postgres) is described in [ADR-0011](0011-three-layer-rls-defence.md).

**Stolen-token window comparison:**

| Scenario | v1.0 (7-day JWT) | S4 (rotation + reuse detection) |
|---|---|---|
| Stolen access token, user returns within minutes | ≤7 days | ≤15 minutes |
| Stolen refresh token, user refreshes once before attacker | ≤7 days | ≤15 min after user's next refresh (reuse detected, chain cascaded) |
| Stolen refresh token, user disappears | ≤7 days (then JWT expires) | ≤7 days (max-session cap kicks in) |
| Stolen refresh token, attacker rotates continuously, user never returns | n/a | ≤7 days (max-session cap is the cieling) |

All cases are at least as good as v1.0; most are dramatically better.

## Consequences

**Positive.**

- A stolen access token gives the attacker ≤15 minutes, not ≤7 days.
- Refresh-token rotation turns a stolen refresh token into a self-reporting incident — the legitimate user's next refresh triggers reuse detection.
- The `revoked_tokens` table and its per-request lookup are gone. `JwtAuthenticationFilter` is now a pure crypto operation, no DB I/O.
- Max-session cap caps the worst case (user disappears) at 7 days — matching v1.0 — while making the common case (user returns) dramatically faster to recover from.
- Append-only triggers + RLS policies + setup-pool isolation give three independent layers of defence around the auth-state table.

**Negative.**

- **Race-detection is aggressive.** Two simultaneous `/refresh` calls from the same client (e.g., a double-click) cause one to succeed and the other to trigger reuse. The legitimate user is logged out as collateral. Trade-off accepted — refresh tokens are not designed for parallel use.
- **Multi-chain semantics.** Login deliberately doesn't revoke prior chains, so multi-device sessions work. Side effect: a user who keeps logging in without logging out has many parallel chains; cleanup relies on the nightly worker (`deleteExpiredRefreshTokens`).
- **Refresh-spam DoS is not handled in v2.0.** A valid refresh token can be hammered to grow `refresh_tokens`. Mitigated by the gateway rate-limiting deferred to v3.0 (see roadmap.md). At ~10 trusted users, not load-bearing.
- **Hard cutover on deploy.** Existing v1.0 7-day JWTs become unusable when this lands. Acceptable per the unused-app stance during v2.0.

## Alternatives considered

- **Keep 7-day JWTs and just add refresh.** Rejected — without rotation, the rotated-token-replay signal doesn't exist. The whole point of rotation is the side-effect of detecting compromise.
- **Refresh that doesn't rotate (single permanent refresh token).** Rejected — same problem. No rotation = no compromise signal.
- **Min-interval idempotent return for DoS protection.** Considered then rejected — non-standard (no major IdP does this), and creates a side-channel that reveals rotation cadence to anyone holding the token. Rate limiting at the gateway is the standard answer.
- **Soft-delete with hard DELETE on chain.** Considered then rejected — DELETE loses the historical rows needed for reuse detection. A replayed rotated token presents to `/refresh`; we need to recognise "this was once issued and has since been rotated" vs "this was never issued." DELETE collapses those two cases.
- **Spring Authorization Server (full OAuth 2.0 IdP).** Rejected — overkill for a personal-finance backend with ~10 users. We're not federating identities; we're operating an auth system for our own app.
- **Encode `user_id` in the refresh token to use the app pool instead of setup pool.** Considered. Pros: refresh runs on the RLS-enforced app pool; tighter security boundary. Cons: refresh token format becomes structured (slight schema leak), and the setup-pool pattern from S1 already handles this case cleanly. Deferred — could revisit if the setup pool ever needs to disappear.

## Operational notes

- **Config knobs** in `JwtProperties`:
  - `app.jwt.access-token-expiry-minutes` (default 15, capped 60)
  - `app.jwt.refresh-token-expiry-days` (default 7, capped 30)
- **Migrations:**
  - [V21__create_refresh_tokens.sql](../../adapters/src/main/resources/db/migration/V21__create_refresh_tokens.sql) — table, triggers, RLS, grants
  - [V22__drop_revoked_tokens.sql](../../adapters/src/main/resources/db/migration/V22__drop_revoked_tokens.sql) — removes superseded table
- **Cleanup job:** `worker/.../CleanupJob.deleteExpiredRefreshTokens` runs daily at 02:20 UTC. Deletes by `expires_at` (catches both expired-active and revoked rows; revocation doesn't shorten `expires_at`).
- **Endpoint contracts:** see [api-contract.md §Login, §Refresh, §Logout](../architecture/api-contract.md). Both `/refresh` and `/logout` accept the refresh token in the request body — no Bearer header required, matching RFC 6749 §6 and RFC 7009.
- **Integration tests:** `api/src/test/java/com/finance/integration/RefreshTokenIntegrationTest.java` covers rotation, reuse detection, max-session cap, multi-chain semantics, and logout edge cases.
