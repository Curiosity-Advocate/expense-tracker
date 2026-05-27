-- Scope junction table for targets. One row per (target, category) pair.
-- participation_type controls whether the category's spending counts toward
-- or is subtracted from the target amount when computing spent/remaining.
--
-- user_id denormalised from the target for direct RLS enforcement.
CREATE TABLE target_categories (
    target_id          UUID        NOT NULL REFERENCES expense_targets(id),
    category_id        UUID        NOT NULL REFERENCES categories(id),
    user_id            UUID        NOT NULL REFERENCES users(id),
    participation_type VARCHAR(20) NOT NULL DEFAULT 'INCLUSIVE',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_participation_type CHECK (participation_type IN ('INCLUSIVE','EXCLUSIVE')),

    PRIMARY KEY (target_id, category_id)
);

CREATE TRIGGER trg_target_categories_set_updated_at
    BEFORE UPDATE ON target_categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_target_categories_lock_created_at
    BEFORE UPDATE ON target_categories
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();
