-- ── mv_monthly_expense_summary ────────────────────────────────────────────────
-- Pre-computes monthly category totals. Used by:
--   - GET /api/v1/expenses/summary?groupBy=CATEGORY
--   - GET /api/v1/targets/{id}/status (spentAmount calculation)
--
-- Joins on partition_registry WHERE status = 'ACTIVE' so archived partition data
-- is automatically excluded without changing this definition.
CREATE MATERIALIZED VIEW mv_monthly_expense_summary AS
SELECT
    ec.user_id,
    EXTRACT(YEAR  FROM e.expense_date)::SMALLINT AS period_year,
    EXTRACT(MONTH FROM e.expense_date)::SMALLINT AS period_month,
    ec.category_id,
    SUM(ec.weight_amount)                         AS total_amount,
    COUNT(DISTINCT e.id)                          AS transaction_count
FROM expense_categories ec
JOIN expenses e
    ON e.id = ec.expense_id
    AND e.expense_date = ec.expense_date
JOIN partition_registry pr
    ON EXTRACT(YEAR FROM e.expense_date)::SMALLINT = pr.partition_year
WHERE e.deleted_at IS NULL
  AND pr.status = 'ACTIVE'
GROUP BY ec.user_id, period_year, period_month, ec.category_id;

-- Required for REFRESH MATERIALIZED VIEW CONCURRENTLY (non-blocking refresh).
CREATE UNIQUE INDEX uq_mv_monthly_expense_summary
    ON mv_monthly_expense_summary (user_id, period_year, period_month, category_id);

-- ── mv_merchant_summary ───────────────────────────────────────────────────────
-- Pre-computes monthly merchant totals. Used by:
--   - GET /api/v1/expenses/summary?groupBy=MERCHANT
CREATE MATERIALIZED VIEW mv_merchant_summary AS
SELECT
    e.user_id,
    EXTRACT(YEAR  FROM e.expense_date)::SMALLINT AS period_year,
    EXTRACT(MONTH FROM e.expense_date)::SMALLINT AS period_month,
    e.merchant_name,
    SUM(e.amount)                                 AS total_amount,
    COUNT(*)                                      AS transaction_count
FROM expenses e
JOIN partition_registry pr
    ON EXTRACT(YEAR FROM e.expense_date)::SMALLINT = pr.partition_year
WHERE e.deleted_at IS NULL
  AND pr.status = 'ACTIVE'
GROUP BY e.user_id, period_year, period_month, e.merchant_name;

CREATE UNIQUE INDEX uq_mv_merchant_summary
    ON mv_merchant_summary (user_id, period_year, period_month, merchant_name);

-- ── Wrapper views for RLS ─────────────────────────────────────────────────────
-- PostgreSQL RLS policies do not apply to materialized views directly.
-- These regular views layer the same session-variable filter on top.
-- The application always queries v_* — never mv_* directly.
CREATE VIEW v_monthly_expense_summary AS
SELECT * FROM mv_monthly_expense_summary
WHERE user_id = current_setting('app.current_user_id', TRUE)::uuid;

CREATE VIEW v_merchant_summary AS
SELECT * FROM mv_merchant_summary
WHERE user_id = current_setting('app.current_user_id', TRUE)::uuid;
