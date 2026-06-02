package com.finance.bankintegration.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Binds bank-integration.* from application.yml. Two nested groups:
//   csv.batch-size                       — rows per processor transaction
//   csv.stale-running-threshold-minutes  — startup recovery trigger window
//
// Sized for personal-use scale (10 users, weekly imports, ≤5000-row files).
// Defaults are chosen for that scale; bumping them needs no code change.
@ConfigurationProperties(prefix = "bank-integration")
@Validated
public record BankIntegrationProperties(@Valid @NotNull Csv csv) {

    public record Csv(
            @Min(1)
            int batchSize,

            @Min(1)
            int staleRunningThresholdMinutes
    ) {}
}
