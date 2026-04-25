-- Cross-user data sharing grants.
-- grantor gives grantee access to their data for a bounded window.
-- Access is scoped (READ_ONLY vs FULL) and always expires.
CREATE TABLE access_grants (
    id                  UUID        PRIMARY KEY,
    grantor_user_id     UUID        NOT NULL REFERENCES users(id),
    grantee_user_id     UUID        NOT NULL REFERENCES users(id),
    access_level        VARCHAR(20) NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_access_level
        CHECK (access_level IN ('READ_ONLY', 'FULL')),

    -- Prevents a user granting themselves access — meaningless and an abuse vector.
    CONSTRAINT chk_no_self_grant
        CHECK (grantor_user_id != grantee_user_id),

    -- 1–10 day window enforced at DB layer, not just API layer.
    -- Even if the API validation is bypassed, the DB rejects it.
    CONSTRAINT chk_expiry_range
        CHECK (expires_at <= created_at + INTERVAL '10 days'
           AND expires_at >= created_at + INTERVAL '1 day')
);

CREATE INDEX idx_access_grants_grantor
    ON access_grants(grantor_user_id);

-- Partial index — only active (non-revoked) grants are looked up at query time.
-- Revoked grants are kept for audit but excluded from the hot path.
CREATE INDEX idx_access_grants_grantee
    ON access_grants(grantee_user_id, expires_at)
    WHERE revoked_at IS NULL;