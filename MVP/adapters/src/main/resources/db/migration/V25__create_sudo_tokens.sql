-- D2 — sudo_tokens table.
--
-- Step-up authentication artefact: a short-lived (15 min) token the grantee
-- must mint by re-entering their password before exercising a D1 access grant.
-- D3's gateway filter validates this token alongside ?asUserId=<grantor>
-- query params to allow the substitution. Without D2, D1 grants exist but
-- cannot be exercised at runtime.
--
-- Schema differs from the original v1.0 doc spec: instead of duplicating
-- grantor_id/grantee_id on the row, we FK to access_grants.id and only
-- denormalise grantee_id (for RLS). The grantor and the grant's current
-- state are always looked up via JOIN on access_grants.

CREATE TABLE sudo_tokens (
    token_hash   VARCHAR(64)  PRIMARY KEY,                  -- SHA-256 hex of the raw token
    grant_id     UUID         NOT NULL REFERENCES access_grants(id),
    grantee_id   UUID         NOT NULL REFERENCES users(id), -- denormalised for RLS
    expires_at   TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- No audit columns (same reasoning as refresh_tokens — security primitive,
-- not user-facing business data). No immutability triggers either — rows
-- are only ever inserted, never updated; expiry is by time, not by event.

-- ── Index ────────────────────────────────────────────────────────────────────
-- Primary key on token_hash covers the verify path. The expires_at index
-- supports the cleanup cron (added with D3 work).

CREATE INDEX idx_sudo_tokens_expires_at ON sudo_tokens (expires_at);

-- ── RLS policy ───────────────────────────────────────────────────────────────
-- Single-clause: only the grantee can see their own sudo tokens. The grantor
-- has no need to list these (they grant; they don't operate as the delegate).
-- Denormalising grantee_id onto this table keeps the policy simple — without
-- it, we'd need a subquery to access_grants which is heavier per row.

ALTER TABLE sudo_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE sudo_tokens FORCE  ROW LEVEL SECURITY;

CREATE POLICY user_isolation ON sudo_tokens AS RESTRICTIVE
    USING (grantee_id = current_setting('app.current_user_id')::uuid);
