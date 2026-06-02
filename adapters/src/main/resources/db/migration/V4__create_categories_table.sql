-- user_id IS NULL  → system category, visible to all users, immutable
-- user_id NOT NULL → user-defined category, private to that user
--
-- No separate is_system column — the null/non-null user_id is the single
-- source of truth. A separate flag would risk inconsistency.
CREATE TABLE categories (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NULL REFERENCES users(id),
    name        VARCHAR(50)     NOT NULL,
    description VARCHAR(255)    NULL,
    parent_id   UUID            NULL REFERENCES categories(id),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- System category names are globally unique.
CREATE UNIQUE INDEX uq_system_category_name
    ON categories (name)
    WHERE user_id IS NULL;

-- User category names are unique per user (two users can both have "PETROL").
CREATE UNIQUE INDEX uq_user_category_name
    ON categories (user_id, name)
    WHERE user_id IS NOT NULL;

CREATE TRIGGER trg_categories_set_updated_at
    BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_categories_lock_created_at
    BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
