-- Enforces at the DB layer that system categories (user_id IS NULL) cannot be
-- modified or deleted by application code. Even if a bug in the service layer
-- tried to update a system category, this trigger would block it.
--
-- The application layer also checks this — this trigger is the defence-in-depth layer.
CREATE OR REPLACE FUNCTION prevent_system_category_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.user_id IS NULL THEN
        RAISE EXCEPTION 'System categories are immutable — they cannot be modified or deleted';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_categories_immutable_system
    BEFORE UPDATE OR DELETE ON categories
    FOR EACH ROW EXECUTE FUNCTION prevent_system_category_mutation();
