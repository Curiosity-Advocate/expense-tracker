-- System-defined categories have user_id = NULL and are visible to all users.
-- User-defined categories are private — user_id = their UUID.
--
-- UNIQUE NULLS NOT DISTINCT is PostgreSQL 15+.
-- Standard UNIQUE treats NULL != NULL, so two system categories
-- could share the same name. NULLS NOT DISTINCT closes that gap.
CREATE TABLE categories (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NULL REFERENCES users(id),
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(255)    NULL,
    is_system   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_category_name_per_user
        UNIQUE NULLS NOT DISTINCT (user_id, name)
);

CREATE INDEX idx_categories_user
    ON categories(user_id)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_categories_system
    ON categories(name)
    WHERE is_system = TRUE;