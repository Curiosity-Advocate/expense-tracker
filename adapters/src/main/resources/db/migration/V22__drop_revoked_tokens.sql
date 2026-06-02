-- S4 sub-step 6 — finishes the revocation-table teardown.
--
-- v1.0 stored revoked JWT JTIs here so the filter could reject logged-out
-- tokens before natural expiry. S4 replaces this with 15-minute access tokens
-- + refresh-token rotation (see V21 and ADR-0009-superseded). The table has
-- been unused since V21 — no INSERTs from PostgresAuthService, no SELECTs
-- from JwtAuthenticationFilter — so this migration just removes the dead
-- schema along with its indexes and triggers (DROP TABLE cascades them).

DROP TABLE IF EXISTS revoked_tokens;
