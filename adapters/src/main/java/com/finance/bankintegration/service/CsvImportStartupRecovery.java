package com.finance.bankintegration.service;

import com.finance.bankintegration.CsvImportProcessor;
import com.finance.bankintegration.config.BankIntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Runs once on API startup. Finds csv_imports rows that were left mid-flight
// when the previous JVM died, resets them to PENDING, and re-kicks-off the
// async processor for each. The reset is necessary because the row's
// in-memory @Async work is gone; the row would otherwise sit in RUNNING
// (or never-started PENDING) forever.
//
// Runs through the setup pool (BYPASSRLS) since there's no user context at
// startup. V29 grants SELECT + UPDATE on csv_imports to expense_setup for
// exactly this case.
//
// Eligibility predicate: status IN ('PENDING','RUNNING') AND submitted_at <
// NOW() - staleThreshold. Catches both crashed-RUNNING and abandoned-PENDING
// (the latter would happen if the previous recovery itself crashed between
// reset and kickoff).
@Component
public class CsvImportStartupRecovery implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CsvImportStartupRecovery.class);

    private final NamedParameterJdbcTemplate     setupJdbc;
    private final CsvImportProcessor             processor;
    private final BankIntegrationProperties      props;

    public CsvImportStartupRecovery(
            @org.springframework.beans.factory.annotation.Qualifier("setupJdbcTemplate") NamedParameterJdbcTemplate setupJdbc,
            CsvImportProcessor processor,
            BankIntegrationProperties props) {
        this.setupJdbc = setupJdbc;
        this.processor = processor;
        this.props     = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        int thresholdMinutes = props.csv().staleRunningThresholdMinutes();
        List<UUID> stale = findStale(thresholdMinutes);

        if (stale.isEmpty()) {
            LOG.debug("CSV import recovery: no stale imports older than {} minutes",
                    thresholdMinutes);
            return;
        }

        LOG.info("CSV import recovery: found {} stale import(s) older than {} minutes; resetting + re-kicking-off",
                stale.size(), thresholdMinutes);

        for (UUID importId : stale) {
            try {
                resetToPending(importId);
                processor.kickoff(importId);
            } catch (Exception e) {
                // One stale row failing recovery shouldn't block the others.
                // The row stays in whatever state the failed reset left it;
                // the next API restart will retry.
                LOG.error("CSV import recovery: failed to recover import {}", importId, e);
            }
        }
    }

    private List<UUID> findStale(int thresholdMinutes) {
        return setupJdbc.queryForList("""
                SELECT id FROM csv_imports
                 WHERE status IN ('PENDING', 'RUNNING')
                   AND submitted_at < NOW() - make_interval(mins => :mins)
                """,
                new MapSqlParameterSource("mins", thresholdMinutes),
                UUID.class);
    }

    private void resetToPending(UUID importId) {
        setupJdbc.update("""
                UPDATE csv_imports
                   SET status             = 'PENDING',
                       started_at         = NULL,
                       imported_count     = 0,
                       deduped_count      = 0,
                       parse_error_count  = 0,
                       last_processed_row = 0,
                       error_message      = NULL
                 WHERE id = :id
                """,
                new MapSqlParameterSource("id", importId));
    }
}
