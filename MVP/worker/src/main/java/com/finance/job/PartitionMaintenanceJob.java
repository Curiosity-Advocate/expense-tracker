package com.finance.job;

import com.finance.alert.JobFailureAlerter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

// Maintains the yearly partitions of the `expenses` table.
// Schema creates partitions for 2023-2028 at install time; this job extends the
// window forward each December and trims it from the back each January.
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private static final int RETAIN_YEARS = 5;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JobFailureAlerter alerter;

    public PartitionMaintenanceJob(JdbcTemplate jdbc, Clock clock, JobFailureAlerter alerter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.alerter = alerter;
    }

    // 01:00 UTC on December 1 — eleven months of slack before the new year.
    // IF NOT EXISTS keeps the job idempotent if it runs more than once.
    @Scheduled(cron = "0 0 1 1 12 *", zone = "UTC")
    public void createNextYearPartition() {
        alerter.executeMonitored("createNextYearPartition", () -> {
            int nextYear = LocalDate.now(clock).getYear() + 1;
            String partition = "expenses_" + nextYear;
            String sql = "CREATE TABLE IF NOT EXISTS " + partition
                    + " PARTITION OF expenses FOR VALUES FROM ('" + nextYear + "-01-01')"
                    + " TO ('" + (nextYear + 1) + "-01-01')";
            jdbc.execute(sql);
            log.info("Ensured partition {} exists", partition);
        });
    }

    // 02:00 UTC on January 1 — detach partitions older than RETAIN_YEARS.
    // DETACH preserves the standalone table; an explicit drop is a manual decision.
    @Scheduled(cron = "0 0 2 1 1 *", zone = "UTC")
    public void archiveOldPartitions() {
        alerter.executeMonitored("archiveOldPartitions", () -> {
            int cutoffYear = LocalDate.now(clock).getYear() - RETAIN_YEARS;
            String partition = "expenses_" + cutoffYear;
            String sql = "ALTER TABLE expenses DETACH PARTITION IF EXISTS " + partition;
            jdbc.execute(sql);
            log.info("Detached partition {} (if it existed)", partition);
        });
    }
}
