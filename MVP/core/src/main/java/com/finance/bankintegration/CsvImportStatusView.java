package com.finance.bankintegration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Snapshot of a csv_imports row, returned by the status endpoint. Captures
// everything the client needs to know about progress; lifecycle-dependent
// fields (startedAt, completedAt, errorMessage) are nullable.
//
// exportedAfterDate matches the parser's validFromDate — tells the client
// which format-era was used to parse this CSV.
public record CsvImportStatusView(
        UUID       importId,
        UUID       bankAccountId,
        String     status,                 // PENDING | RUNNING | COMPLETED | FAILED
        LocalDate  exportedOnDate,
        String     parserVersionTag,
        int        importedCount,
        int        dedupedCount,
        int        parseErrorCount,
        int        lastProcessedRow,
        String     errorMessage,           // populated only when status = FAILED
        Instant    submittedAt,
        Instant    startedAt,              // null until status -> RUNNING
        Instant    completedAt             // null until terminal
) {
}
