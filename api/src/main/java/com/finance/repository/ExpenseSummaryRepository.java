package com.finance.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ExpenseSummaryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ExpenseSummaryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> summariseByCategory(
            UUID userId, LocalDate dateFrom, LocalDate dateTo) {

        String sql = """
                SELECT
                    category_name                                           AS group_key,
                    SUM(total_amount)                                       AS total_amount,
                    SUM(transaction_count)                                  AS transaction_count,
                    ROUND(SUM(total_amount) / SUM(SUM(total_amount))
                        OVER () * 100, 1)                                   AS percentage_of_total,
                    MAX(last_transaction_date)                              AS last_transaction_date
                FROM mv_monthly_expense_summary
                WHERE user_id = :userId
                  AND period_month >= DATE_TRUNC('month', :dateFrom::date)
                  AND period_month <= DATE_TRUNC('month', :dateTo::date)
                GROUP BY category_name
                ORDER BY total_amount DESC
                """;

        return jdbcTemplate.queryForList(sql, params(userId, dateFrom, dateTo));
    }

    public List<Map<String, Object>> summariseByMerchant(
            UUID userId, LocalDate dateFrom, LocalDate dateTo) {

        String sql = """
                SELECT
                    merchant_name                                           AS group_key,
                    SUM(total_amount)                                       AS total_amount,
                    SUM(transaction_count)                                  AS transaction_count,
                    ROUND(SUM(total_amount) / SUM(SUM(total_amount))
                        OVER () * 100, 1)                                   AS percentage_of_total
                FROM mv_merchant_summary
                WHERE user_id = :userId
                  AND period_month >= DATE_TRUNC('month', :dateFrom::date)
                  AND period_month <= DATE_TRUNC('month', :dateTo::date)
                GROUP BY merchant_name
                ORDER BY total_amount DESC
                """;

        return jdbcTemplate.queryForList(sql, params(userId, dateFrom, dateTo));
    }

    public List<Map<String, Object>> summariseByMonth(
            UUID userId, LocalDate dateFrom, LocalDate dateTo) {

        String sql = """
                SELECT
                    TO_CHAR(period_month, 'YYYY-MM')                        AS group_key,
                    SUM(total_amount)                                        AS total_amount,
                    SUM(transaction_count)                                   AS transaction_count,
                    ROUND(SUM(total_amount) / SUM(SUM(total_amount))
                        OVER () * 100, 1)                                    AS percentage_of_total
                FROM mv_monthly_expense_summary
                WHERE user_id = :userId
                  AND period_month >= DATE_TRUNC('month', :dateFrom::date)
                  AND period_month <= DATE_TRUNC('month', :dateTo::date)
                GROUP BY period_month
                ORDER BY period_month ASC
                """;

        return jdbcTemplate.queryForList(sql, params(userId, dateFrom, dateTo));
    }

    public BigDecimal getTotalAmount(UUID userId, LocalDate dateFrom, LocalDate dateTo) {

        String sql = """
                SELECT COALESCE(SUM(total_amount), 0)
                FROM mv_monthly_expense_summary
                WHERE user_id = :userId
                  AND period_month >= DATE_TRUNC('month', :dateFrom::date)
                  AND period_month <= DATE_TRUNC('month', :dateTo::date)
                """;

        return jdbcTemplate.queryForObject(sql, params(userId, dateFrom, dateTo),
                BigDecimal.class);
    }

    private MapSqlParameterSource params(UUID userId, LocalDate dateFrom, LocalDate dateTo) {
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("dateFrom", dateFrom)
                .addValue("dateTo", dateTo);
    }
}