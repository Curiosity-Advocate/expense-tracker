# ADR-0009 — JWT revocation via `jti` table

> **Context:** Expands [overview.md §6](../overview.md#6-how-users-interact-with-the-system). Driven by: F3, N5.

**Status:** Accepted. Adopted in v1.0.

---

## Context

JWTs are stateless by design — the server has no memory of issued tokens. This is normally a virtue: any node can validate a token without a session store. But it creates a problem for logout: the user wants the token to stop working *before* its expiry, and the server cannot revoke what it does not track.

Two patterns exist for invalidating a JWT before expiry:

1. **Short-lived access tokens + refresh tokens.** Access tokens expire in 15 minutes; logout deletes the refresh token. The access token remains valid until expiry, but the blast radius is small.
2. **Server-side revocation list.** A table of revoked `jti` (JWT ID) claims. The auth filter checks the table on every request.

## Decision

Adopt **server-side revocation via a `revoked_tokens` table** with `jti` as the primary key.

- Every JWT is issued with a random `jti` claim.
- Logout writes `(jti, user_id, expires_at)` to `revoked_tokens`.
- `JwtAuthenticationFilter` checks `revoked_tokens` on every request as the fourth of its five gates. A hit returns 401.
- A nightly cleanup job removes rows where `expires_at <= NOW()` — those tokens would be rejected by expiry anyway.

## Consequences

**Positive.**
- Logout is immediate. A revoked token is rejected on the very next request.
- Compromised tokens can be revoked individually without invalidating others (unlike rotating the signing key, which kills every active session).
- The `jti` PK with an explicit index makes the lookup O(1).

**Negative.**
- Every authenticated request does one DB lookup. At MVP scale (N16) this is trivial. At higher scale, a Redis cache in front of the table would be the next step.
- The system is no longer "stateless JWT" in the purest sense — there is a state store the filter must consult. The trade is intentional.

## Alternatives considered

- **Short-lived access tokens with refresh tokens.** The industry-standard approach. Deferred to v2.0 — adds complexity (refresh endpoint, refresh-token storage, refresh-token revocation) for marginal gain at MVP scale. v1.0 uses 7-day tokens with explicit revocation.
- **Rotate the JWT signing key to revoke all tokens.** Effective but indiscriminate — every user is logged out. Useful for emergencies, not for normal logout.
- **Stateless logout (do nothing server-side).** Rejected — a stolen token would remain valid until expiry. Unacceptable for financial data.
