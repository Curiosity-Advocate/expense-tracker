-- D1 — access_grants table.
--
-- Records that user A (grantor) has granted user B (grantee) the ability to
-- act on A's data via the delegation mechanism. D1 ships the persistence and
-- CRUD API only; D2 (sudo_tokens) and D3 (asUserId gateway filter) turn
-- delegation on as a runtime behaviour. Until then, grants exist as records
-- but cannot be used to actually act on someone else's data.

CREATE TABLE access_grants (
    id           UUID         PRIMARY KEY,
    grantor_id   UUID         NOT NULL REFERENCES users(id),
    grantee_id   UUID         NOT NULL REFERENCES users(id),

    -- v2.0 supports a single level. Future levels (READ_ONLY, scope-limited,
    -- etc.) will require dropping/recreating the constraint in a later migration.
    access_level VARCHAR(20)  NOT NULL CHECK (access_level IN ('READ_WRITE')),

    -- Set at create time to NOW() + expiresInDays. Bounded by service-layer
    -- validation (1-30 days); chk_expires_in_future is the DB backstop.
    expires_at   TIMESTAMPTZ  NOT NULL,

    -- Soft revocation. NULL = active; set means explicitly ended (by either
    -- grantor or grantee). Grants are never physically deleted.
    revoked_at   TIMESTAMPTZ  NULL,

    -- Audit columns (S5 conventions). Populated by set_audit_user / locked
    -- by lock_created_by triggers below.
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by   UUID         NULL REFERENCES users(id),
    modified_by  UUID         NULL REFERENCES users(id),

    CONSTRAINT chk_no_self_grant      CHECK (grantor_id <> grantee_id),
    CONSTRAINT chk_expires_in_future  CHECK (expires_at > created_at)
);

-- Standard timestamp triggers from V1.
CREATE TRIGGER trg_access_grants_set_updated_at
    BEFORE UPDATE ON access_grants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_access_grants_lock_created_at
    BEFORE UPDATE ON access_grants
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();

-- Audit-user triggers from V23.
CREATE TRIGGER trg_access_grants_set_audit_user
    BEFORE INSERT OR UPDATE ON access_grants
    FOR EACH ROW EXECUTE FUNCTION set_audit_user();

CREATE TRIGGER trg_access_grants_lock_created_by
    BEFORE UPDATE ON access_grants
    FOR EACH ROW EXECUTE FUNCTION lock_created_by();

-- ── Indexes ──────────────────────────────────────────────────────────────────
-- Two partial indexes for the most common queries ("my active grants given"
-- and "my active grants received") keep the on-disk index size proportional
-- to active grants only. The expires_at index supports the cleanup cron
-- (added in a later migration alongside D2/D3 cleanup work).

CREATE INDEX idx_access_grants_grantor_active
    ON access_grants (grantor_id) WHERE revoked_at IS NULL;

CREATE INDEX idx_access_grants_grantee_active
    ON access_grants (grantee_id) WHERE revoked_at IS NULL;

CREATE INDEX idx_access_grants_expires_at
    ON access_grants (expires_at);

-- ── RLS policy ───────────────────────────────────────────────────────────────
-- Dual-clause: unlike every other tenant-scoped table where RLS is
-- "WHERE user_id = current_user", access_grants is "WHERE current_user is
-- either the grantor OR the grantee". A user sees grants they're party to,
-- in either role. The same USING clause applies to UPDATE / DELETE / INSERT
-- (Postgres uses USING as WITH CHECK by default for inserts), so a user
-- can only create grants where they are themselves party — combined with
-- service-layer enforcement that the current user must be the grantor,
-- the create path is doubly bounded.

ALTER TABLE access_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_grants FORCE  ROW LEVEL SECURITY;

CREATE POLICY user_isolation ON access_grants AS RESTRICTIVE
    USING (grantor_id = current_setting('app.current_user_id')::uuid
        OR grantee_id = current_setting('app.current_user_id')::uuid);
