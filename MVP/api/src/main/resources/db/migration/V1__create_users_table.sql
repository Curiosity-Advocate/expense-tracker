-- ── Shared trigger functions ──────────────────────────────────────────────────
-- Defined here because they are used by every subsequent table.

-- DB owns updated_at. Application code never sets this field — the trigger fires
-- on every UPDATE regardless of which column changed.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Prevents created_at from being changed after the row is inserted.
-- Enforced at DB layer — bypasses application code entirely.
CREATE OR REPLACE FUNCTION lock_created_at()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'created_at is immutable — it cannot be changed after insert';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── Users table ───────────────────────────────────────────────────────────────
CREATE TABLE users (
    id                  UUID            PRIMARY KEY,
    username            VARCHAR(50)     NOT NULL UNIQUE,
    email               VARCHAR(255)    NOT NULL UNIQUE,
    password_hash       VARCHAR(255)    NOT NULL,
    email_verified      BOOLEAN         NOT NULL DEFAULT FALSE,
    is_discoverable     BOOLEAN         NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    failed_login_count  INT             NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ     NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_username_length CHECK (LENGTH(username) BETWEEN 3 AND 50),
    CONSTRAINT chk_email_format    CHECK (email ~* '^[^@]+@[^@]+\.[^@]+$')
);

CREATE TRIGGER trg_users_lock_created_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
