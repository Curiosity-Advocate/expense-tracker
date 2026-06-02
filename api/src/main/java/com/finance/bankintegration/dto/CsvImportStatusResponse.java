package com.finance.bankintegration.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 200 OK response for GET /csv-imports/{id}. Snapshot of progress; fields
// nullable per their lifecycle (startedAt is null until status=RUNNING etc).
public record CsvImportStatusResponse(
        UUID       importId,
        UUID       bankAccountId,
        String     status,
        LocalDate  exportedOnDate,
        String     parserVersionTag,
        int        importedCount,
        int        dedupedCount,
        int        parseErrorCount,
        int        lastProcessedRow,
        String     errorMessage,
        Instant    submittedAt,
        Instant    startedAt,
        Instant    completedAt
) {
}
