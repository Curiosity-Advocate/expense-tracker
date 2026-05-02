CREATE OR REPLACE FUNCTION prevent_system_category_modification()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.is_system = TRUE THEN
        RAISE EXCEPTION 'System categories cannot be modified or deleted';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_system_categories
    BEFORE UPDATE OR DELETE ON categories
    FOR EACH ROW EXECUTE FUNCTION prevent_system_category_modification();