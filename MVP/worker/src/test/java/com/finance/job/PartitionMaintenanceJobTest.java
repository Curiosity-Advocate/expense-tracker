package com.finance.job;

import com.finance.alert.JobFailureAlerter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartitionMaintenanceJobTest {

    @Mock JdbcTemplate jdbc;
    @Mock Clock clock;
    @Mock JobFailureAlerter alerter;

    @InjectMocks PartitionMaintenanceJob job;

    @BeforeEach
    void wireAlerter() {
        // Mocked alerter would otherwise swallow the Runnable; invoke it so the
        // JDBC calls actually fire.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(alerter).executeMonitored(anyString(), any(Runnable.class));
    }

    // v1.1 #3 — partition creation cron: on December 1 of year Y, must create
    // partition expenses_(Y+1) spanning Y+1-01-01 to Y+2-01-01.
    @Test
    void createNextYearPartition_executesCreateSql_forClockYearPlusOne() {
        when(clock.instant()).thenReturn(Instant.parse("2028-12-01T01:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        job.createNextYearPartition();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS expenses_2029")
                .contains("PARTITION OF expenses")
                .contains("'2029-01-01'")
                .contains("'2030-01-01'");
    }

    // v1.1 #3 — partition archival cron: on January 1 of year Y, must detach
    // partition expenses_(Y - RETAIN_YEARS). With RETAIN_YEARS=5 and Y=2029, that
    // is expenses_2024.
    @Test
    void archiveOldPartitions_executesDetachSql_forClockYearMinusRetention() {
        when(clock.instant()).thenReturn(Instant.parse("2029-01-01T02:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        job.archiveOldPartitions();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("ALTER TABLE expenses")
                .contains("DETACH PARTITION IF EXISTS expenses_2024");
    }
}
