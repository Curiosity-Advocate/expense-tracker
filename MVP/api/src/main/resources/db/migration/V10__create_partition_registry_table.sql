-- Tracks which year partitions exist and whether they are active or archived.
-- Materialized views join on this table to automatically exclude archived data —
-- changing a partition's status changes what the views aggregate without touching
-- the view definition.
--
-- No RLS — system-wide infrastructure shared across all users.
CREATE TABLE partition_registry (
    partition_year  SMALLINT    PRIMARY KEY,
    status          VARCHAR(10) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_partition_status CHECK (status IN ('ACTIVE','ARCHIVED'))
);

CREATE TRIGGER trg_partition_registry_set_updated_at
    BEFORE UPDATE ON partition_registry
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_partition_registry_lock_created_at
    BEFORE UPDATE ON partition_registry
    FOR EACH ROW EXECUTE FUNCTION lock_created_at();

-- Seed one row per partition created in V5.
INSERT INTO partition_registry (partition_year, status) VALUES
    (2023, 'ACTIVE'),
    (2024, 'ACTIVE'),
    (2025, 'ACTIVE'),
    (2026, 'ACTIVE'),
    (2027, 'ACTIVE'),
    (2028, 'ACTIVE');
