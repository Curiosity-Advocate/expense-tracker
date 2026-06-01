package com.finance.bankintegration.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Response body for GET / POST / PATCH on a CSV import connection.
public record CsvImportConnectionResponse(
        UUID       bankAccountId,
        String     bankId,
        String     csvExportUrl,
        Instant    lastImportedAt,
        LocalDate  lastDateTo
) {
}
