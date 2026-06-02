package com.finance.bankintegration.controller;

import com.finance.bankintegration.CsvImportStatusView;
import com.finance.bankintegration.CsvImportSubmissionResult;
import com.finance.bankintegration.dto.CsvImportStatusResponse;
import com.finance.bankintegration.dto.CsvImportSubmissionResponse;
import com.finance.bankintegration.service.CsvImportService;
import com.finance.domain.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

// Upload + status endpoints. Two URL roots:
//   POST /api/v1/bank-accounts/{id}/csv-import     ← scoped to one account
//   GET  /api/v1/bank-data/csv-imports/{importId}  ← global lookup by import id
//
// 202 Accepted on upload; client polls the status URL.
@RestController
@Tag(name = "CSV Import")
public class CsvImportController {

    private final CsvImportService csvImportService;
    private final Clock            clock;

    public CsvImportController(CsvImportService csvImportService, Clock clock) {
        this.csvImportService = csvImportService;
        this.clock            = clock;
    }

    @PostMapping("/api/v1/bank-accounts/{bankAccountId}/csv-import")
    @Operation(summary = "Upload a CSV for this bank account; returns 202 Accepted with a statusUrl to poll")
    public ResponseEntity<CsvImportSubmissionResponse> upload(
            @PathVariable UUID bankAccountId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "exportedOnDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate exportedOnDate,
            @AuthenticationPrincipal UserPrincipal principal) {

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded CSV", e);
        }

        // Default per the user's "if not provided, assume today" decision.
        LocalDate effectiveDate = (exportedOnDate != null)
                ? exportedOnDate
                : LocalDate.now(clock.withZone(ZoneOffset.UTC));

        CsvImportSubmissionResult result = csvImportService.upload(
                bankAccountId, bytes, effectiveDate, principal.userId());

        CsvImportSubmissionResponse body = new CsvImportSubmissionResponse(
                result.importId(),
                "/api/v1/bank-data/csv-imports/" + result.importId(),
                result.parserVersionTag(),
                result.exportedOnDate(),
                result.submittedAt());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/api/v1/bank-data/csv-imports/{importId}")
    @Operation(summary = "Poll the status of a CSV import")
    public ResponseEntity<CsvImportStatusResponse> status(@PathVariable UUID importId) {
        CsvImportStatusView v = csvImportService.status(importId);
        return ResponseEntity.ok(new CsvImportStatusResponse(
                v.importId(),
                v.bankAccountId(),
                v.status(),
                v.exportedOnDate(),
                v.parserVersionTag(),
                v.importedCount(),
                v.dedupedCount(),
                v.parseErrorCount(),
                v.lastProcessedRow(),
                v.errorMessage(),
                v.submittedAt(),
                v.startedAt(),
                v.completedAt()));
    }
}
