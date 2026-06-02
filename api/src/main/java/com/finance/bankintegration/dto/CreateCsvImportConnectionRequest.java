package com.finance.bankintegration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// POST body for creating a CSV import connection.
// bankId required; csvExportUrl optional (the user's bookmark, set later via PATCH).
public record CreateCsvImportConnectionRequest(
        @NotBlank
        String bankId,

        @Size(max = 500)
        String csvExportUrl
) {
}
