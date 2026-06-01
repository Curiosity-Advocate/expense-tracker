package com.finance.bankintegration.exception;

import java.util.UUID;

// Thrown when the user attempts a CSV import for a bank_account that
// has no csv_import_connections row. Mapped to 404 — the resource the
// user is acting on (the CSV connection for this account) doesn't exist.
//
// Also thrown when the account exists but RLS hides it from the current
// user; the distinction would leak whether someone else owns the account.
public class CsvImportNotConfiguredException extends RuntimeException {

    public CsvImportNotConfiguredException(UUID bankAccountId) {
        super("No CSV import connection found for bank account " + bankAccountId);
    }
}
