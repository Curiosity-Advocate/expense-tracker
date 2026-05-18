package com.finance.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Refreshes materialized views after the nightly cleanup so summary queries
// reflect today's data. CONCURRENTLY avoids locking reads during the refresh.
// Requires the unique indexes defined in V11__create_materialized_views.sql.
@Component
public class MaterializedViewRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewRefreshJob.class);

    private final JdbcTemplate jdbc;

    public MaterializedViewRefreshJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    public void refresh() {
        long start = System.currentTimeMillis();
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_expense_summary");
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_merchant_summary");
        log.info("Materialized views refreshed in {}ms", System.currentTimeMillis() - start);
    }
}
