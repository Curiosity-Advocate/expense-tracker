package com.finance.bankintegration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Returned by CsvImportService.upload() — the sync part of the upload path
// completes immediately. The controller wraps this into the 202 Accepted
// response body.
//
// importId          — handle the client uses to poll status
// parserVersionTag  — which parser will run; exposed early so the client
//                     sees the format-era assumption right away (e.g.
//                     "this CSV is going to be parsed as csv_cba_v1")
// exportedOnDate    — what we recorded (either supplied by the client or
//                     defaulted to today)
// submittedAt       — server-side timestamp of the POST
public record CsvImportSubmissionResult(
        UUID       importId,
        String     parserVersionTag,
        LocalDate  exportedOnDate,
        Instant    submittedAt
) {
}
