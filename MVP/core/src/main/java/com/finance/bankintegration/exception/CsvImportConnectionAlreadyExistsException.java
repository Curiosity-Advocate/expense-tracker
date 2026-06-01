package com.finance.bankintegration.exception;

import java.util.UUID;

// Thrown when the user attempts to create a CSV import connection for a
// bank_account that already has one. Mapped to 409 — the resource conflict
// is "exactly one CSV connection per bank account" per the V28 PK design.
// The user's path forward is either DELETE the existing connection and POST
// a new one, or PATCH the existing one to the new values.
public class CsvImportConnectionAlreadyExistsException extends RuntimeException {

    public CsvImportConnectionAlreadyExistsException(UUID bankAccountId) {
        super("CSV import connection already exists for bank account " + bankAccountId);
    }
}
