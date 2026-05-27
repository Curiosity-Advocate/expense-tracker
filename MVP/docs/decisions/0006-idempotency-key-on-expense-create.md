# ADR-0006 — Idempotency key on expense create

> **Context:** Expands [overview.md §6](../overview.md#6-how-users-interact-with-the-system). Driven by: F27.

**Status:** Accepted. Adopted in v1.0.

---

## Context

`POST /api/v1/expenses` is non-idempotent at the protocol level — every call creates a new expense. A real-world client (mobile, web) experiences network failures: the request reached the server, was processed, but the response was lost. The client cannot tell whether the create succeeded.

Without protection, the client's retry creates a duplicate expense. The user sees the same purchase twice. Financial reports get distorted. Manual cleanup is annoying and error-prone.

## Decision

Adopt **client-generated idempotency keys** on `POST /api/v1/expenses`.

- The client generates a UUID and sends it as `idempotencyKey` in the request body.
- The server stores `(user_id, idempotency_key) → expense_id, expense_date` in `expense_idempotency_keys` with a 24-hour TTL.
- On a duplicate request with the same key inside the TTL, the server returns the **original** expense with `201 Created` and does not create a new one.
- Keys are scoped per user via composite PK `(user_id, idempotency_key)` — the same UUID from two different users does not conflict.
- The `idempotency_key` is optional; if the client omits it, no protection is applied.

## Consequences

**Positive.**
- Safe client retries — the server is honest about which writes succeeded.
- No duplicate expenses from network blips. The single biggest source of bad data is closed.
- Composite PK guarantees no cross-user collision even with poorly-generated UUIDs.

**Negative.**
- The client must remember to generate and send a key. A client that does not opt in gets no protection.
- The `expense_idempotency_keys` table grows linearly with traffic. Mitigated by the 24-hour TTL and the nightly cleanup job (F36).

## Alternatives considered

- **Server-generated keys.** Rejected — the whole point is that the client wants to retry safely after a failed response. A server-generated key is only known after success.
- **Hash the request body as the key.** Rejected — a legitimate user creating two identical expenses (same merchant, same amount, same day) would have the second one silently dropped.
- **Conditional on `If-None-Match` header.** Standard HTTP idempotency mechanism but requires the client to track ETags. The body-level UUID is simpler and equally safe.
- **Hash the idempotency key before storing.** Rejected — idempotency keys are client-generated identifiers, not credentials. Plaintext storage is no different from storing the `jti` claim of a JWT. No security gain from hashing.
