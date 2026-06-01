package com.finance.bankintegration.controller;

import com.finance.bankintegration.dto.CreateCsvImportConnectionRequest;
import com.finance.bankintegration.dto.CsvImportConnectionResponse;
import com.finance.bankintegration.dto.UpdateCsvImportConnectionRequest;
import com.finance.bankintegration.entity.CsvImportConnectionEntity;
import com.finance.bankintegration.service.CsvImportConnectionService;
import com.finance.domain.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// CRUD for the CSV import connection on a bank account.
//
// URL shape: /api/v1/bank-accounts/{id}/csv-import-connection — same prefix
// as the upload endpoint, scoped to one account.
@RestController
@RequestMapping("/api/v1/bank-accounts/{bankAccountId}/csv-import-connection")
@Tag(name = "CSV Import Connection")
public class CsvImportConnectionController {

    private final CsvImportConnectionService connections;

    public CsvImportConnectionController(CsvImportConnectionService connections) {
        this.connections = connections;
    }

    @PostMapping
    @Operation(summary = "Set up CSV import for this bank account")
    public ResponseEntity<CsvImportConnectionResponse> create(
            @PathVariable UUID bankAccountId,
            @Valid @RequestBody CreateCsvImportConnectionRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {

        CsvImportConnectionEntity row = connections.create(
                bankAccountId, req.bankId(), req.csvExportUrl(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(row));
    }

    @GetMapping
    @Operation(summary = "Get the CSV import config for this bank account")
    public ResponseEntity<CsvImportConnectionResponse> get(@PathVariable UUID bankAccountId) {
        return ResponseEntity.ok(toResponse(connections.get(bankAccountId)));
    }

    @PatchMapping
    @Operation(summary = "Update bank id or bookmark URL on an existing CSV connection")
    public ResponseEntity<CsvImportConnectionResponse> update(
            @PathVariable UUID bankAccountId,
            @Valid @RequestBody UpdateCsvImportConnectionRequest req) {

        return ResponseEntity.ok(toResponse(
                connections.update(bankAccountId, req.bankId(), req.csvExportUrl())));
    }

    @DeleteMapping
    @Operation(summary = "Remove CSV import for this bank account (idempotent)")
    public ResponseEntity<Void> delete(@PathVariable UUID bankAccountId) {
        connections.delete(bankAccountId);
        return ResponseEntity.noContent().build();
    }

    private static CsvImportConnectionResponse toResponse(CsvImportConnectionEntity row) {
        return new CsvImportConnectionResponse(
                row.getBankAccountId(),
                row.getBankId(),
                row.getCsvExportUrl(),
                row.getLastImportedAt(),
                row.getLastDateTo());
    }
}
