-- Materialized views are pre-computed query results stored on disk.
-- Reads hit these instead of scanning the raw expenses table.
-- Refreshed after every expense write at current scale via AOP.
-- CONCURRENTLY means reads are never blocked during a refresh —
-- queries continue serving old data while the refresh runs.
-- This is what makes bounded staleness work without blocking reads.
--
-- Both views join on partition_registry to exclude archived partitions.
-- When a partition is marked ARCHIVED, it disappears from these views
-- automatically — no view definition change needed.

CREATE MATERIALIZED VIEW mv_monthly_expense_summary AS
SELECT
    e.user_id,
    DATE_TRUNC('month', e.expense_date)     AS period_month,
    ec.category_id,
    c.name                                  AS category_name,
    COUNT(DISTINCT e.id)                    AS transaction_count,
    SUM(ec.weight_amount)                   AS total_amount,
    AVG(ec.weight_amount)                   AS avg_amount,
    MIN(e.expense_date)                     AS first_transaction_date,
    MAX(e.expense_date)                     AS last_transaction_date
FROM expenses e
JOIN expense_categories ec
    ON ec.expense_id = e.id
    AND ec.expense_date = e.expense_date
JOIN categories c
    ON c.id = ec.category_id
JOIN partition_registry pr
    ON pr.year = EXTRACT(YEAR FROM e.expense_date)
WHERE e.deleted_at IS NULL
  AND pr.status = 'ACTIVE'
GROUP BY
    e.user_id,
    DATE_TRUNC('month', e.expense_date),
    ec.category_id,
    c.name;

-- Unique index is required for REFRESH CONCURRENTLY.
-- PostgreSQL needs a way to identify each row uniquely
-- during a concurrent refresh to diff old vs new data.
-- Without this index, CONCURRENTLY is not available and
-- refreshes would lock the view for reads.
CREATE UNIQUE INDEX idx_mv_monthly_summary
    ON mv_monthly_expense_summary(user_id, period_month, category_id);

-- Drives group-by-merchant queries and the expense summary endpoint.
CREATE MATERIALIZED VIEW mv_merchant_summary AS
SELECT
    e.user_id,
    e.merchant_name,
    DATE_TRUNC('month', e.expense_date)     AS period_month,
    COUNT(*)                                AS transaction_count,
    SUM(e.amount)                           AS total_amount
FROM expenses e
JOIN partition_registry pr
    ON pr.year = EXTRACT(YEAR FROM e.expense_date)
WHERE e.deleted_at IS NULL
  AND pr.status = 'ACTIVE'
GROUP BY
    e.user_id,
    e.merchant_name,
    DATE_TRUNC('month', e.expense_date);

CREATE UNIQUE INDEX idx_mv_merchant_summary
    ON mv_merchant_summary(user_id, period_month, merchant_name);