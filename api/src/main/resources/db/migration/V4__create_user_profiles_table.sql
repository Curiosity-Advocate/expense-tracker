-- User profile data, separate from the auth-critical users table.
-- Splitting these means the Auth module owns credentials,
-- User Management module owns everything else.
-- A join is cheap; mixing concerns in one table is not.
CREATE TABLE user_profiles (
    user_id         UUID            PRIMARY KEY REFERENCES users(id),
    display_name    VARCHAR(100)    NULL,
    timezone        VARCHAR(50)     NOT NULL DEFAULT 'Australia/Sydney',
    currency_code   CHAR(3)         NOT NULL DEFAULT 'AUD',
    preferences     JSONB           NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_user_profiles_set_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();