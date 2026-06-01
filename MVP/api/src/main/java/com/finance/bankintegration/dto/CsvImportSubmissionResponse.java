package com.finance.bankintegration.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 202 Accepted response for POST /csv-import. Async processing has been
// queued; client polls statusUrl for completion.
public record CsvImportSubmissionResponse(
        UUID       importId,
        String     statusUrl,
        String     parserVersionTag,
        LocalDate  exportedOnDate,
        Instant    submittedAt
) {
}
