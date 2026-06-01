package com.finance.bankintegration.exception;

import java.time.Instant;

// Thrown when the user attempts a CSV import for an account that was
// imported less than 7 days ago. The 7-day window is product policy
// ("once-per-week refresh") not a security limit. nextAllowedAt is
// surfaced to the user so they know exactly when to retry.
public class CsvImportRateLimitedException extends RuntimeException {

    private final Instant nextAllowedAt;

    public CsvImportRateLimitedException(Instant nextAllowedAt) {
        super("CSV import allowed once every 7 days per account. Next allowed at: " + nextAllowedAt);
        this.nextAllowedAt = nextAllowedAt;
    }

    public Instant getNextAllowedAt() {
        return nextAllowedAt;
    }
}
