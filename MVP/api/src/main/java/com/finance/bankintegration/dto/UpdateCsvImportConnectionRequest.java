package com.finance.bankintegration.dto;

import jakarta.validation.constraints.Size;

// PATCH body. Both fields are optional — null means "leave unchanged."
// To clear csvExportUrl, send an empty string.
public record UpdateCsvImportConnectionRequest(
        String bankId,

        @Size(max = 500)
        String csvExportUrl
) {
}
