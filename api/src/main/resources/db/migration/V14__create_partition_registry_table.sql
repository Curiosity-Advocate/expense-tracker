-- Tracks which year partitions exist and their status.
-- Materialized views join on this to automatically exclude
-- archived partitions — no view definition change needed
-- when a partition gets archived.
CREATE TABLE partition_registry (
    partition_name  VARCHAR(50)  PRIMARY KEY,
    year            INT          NOT NULL UNIQUE,
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    archived_at     TIMESTAMPTZ  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_partition_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

-- Seed with the partitions created in V7
INSERT INTO partition_registry (partition_name, year, status) VALUES
    ('expenses_2021', 2021, 'ACTIVE'),
    ('expenses_2022', 2022, 'ACTIVE'),
    ('expenses_2023', 2023, 'ACTIVE'),
    ('expenses_2024', 2024, 'ACTIVE'),
    ('expenses_2025', 2025, 'ACTIVE'),
    ('expenses_2026', 2026, 'ACTIVE');