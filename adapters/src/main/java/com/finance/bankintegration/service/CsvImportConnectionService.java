package com.finance.bankintegration.service;

import com.finance.bankintegration.entity.CsvImportConnectionEntity;
import com.finance.bankintegration.exception.CsvImportConnectionAlreadyExistsException;
import com.finance.bankintegration.exception.CsvImportNotConfiguredException;
import com.finance.bankintegration.exception.UnknownBankException;
import com.finance.bankintegration.repository.CsvImportConnectionRepository;
import com.finance.repository.BankAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// CRUD for csv_import_connections. Separate from CsvImportService — that
// owns upload + status; this owns the connection lifecycle.
//
// All operations run on the app pool with RLS scoping. The bank_account
// existence check goes through BankAccountRepository (also app-pool +
// RLS), so a bank account that doesn't belong to the user appears as
// "not found" — same shape as a bank account that genuinely doesn't exist.
//
// CsvImportNotConfiguredException        → 404 (also for unknown bank account)
// CsvImportConnectionAlreadyExistsException → 409
// IllegalArgumentException               → 422 (unknown bank id)
@Service
public class CsvImportConnectionService {

    private final CsvImportConnectionRepository connections;
    private final BankAccountRepository         bankAccounts;
    private final CsvParserRegistry             parsers;

    public CsvImportConnectionService(CsvImportConnectionRepository connections,
                                       BankAccountRepository bankAccounts,
                                       CsvParserRegistry parsers) {
        this.connections   = connections;
        this.bankAccounts  = bankAccounts;
        this.parsers       = parsers;
    }

    @Transactional
    public CsvImportConnectionEntity create(UUID bankAccountId, String bankId, String csvExportUrl, UUID currentUserId) {
        requireKnownBank(bankId);

        // Validate the bank_account exists for this user. RLS hides accounts
        // belonging to other users; absent = missing-or-not-yours, same response.
        if (bankAccounts.findById(bankAccountId).isEmpty()) {
            throw new CsvImportNotConfiguredException(bankAccountId);
        }

        if (connections.findById(bankAccountId).isPresent()) {
            throw new CsvImportConnectionAlreadyExistsException(bankAccountId);
        }

        CsvImportConnectionEntity row = new CsvImportConnectionEntity();
        row.setBankAccountId(bankAccountId);
        row.setUserId(currentUserId);
        row.setBankId(bankId);
        row.setCsvExportUrl(csvExportUrl);
        return connections.save(row);
    }

    @Transactional(readOnly = true)
    public CsvImportConnectionEntity get(UUID bankAccountId) {
        return connections.findById(bankAccountId)
                .orElseThrow(() -> new CsvImportNotConfiguredException(bankAccountId));
    }

    @Transactional
    public CsvImportConnectionEntity update(UUID bankAccountId, String newBankId, String newCsvExportUrl) {
        CsvImportConnectionEntity row = connections.findById(bankAccountId)
                .orElseThrow(() -> new CsvImportNotConfiguredException(bankAccountId));

        // Null in either field means "not provided" — leave existing value.
        // To clear csv_export_url, the client sends an empty string; we treat
        // empty-string explicitly as a clear so the bookmark can be removed.
        if (newBankId != null) {
            requireKnownBank(newBankId);
            row.setBankId(newBankId);
        }
        if (newCsvExportUrl != null) {
            row.setCsvExportUrl(newCsvExportUrl.isBlank() ? null : newCsvExportUrl);
        }
        return connections.save(row);
    }

    // Idempotent — silent success even when no row exists. Matches the
    // logout / DELETE-grant pattern used elsewhere.
    @Transactional
    public void delete(UUID bankAccountId) {
        connections.findById(bankAccountId).ifPresent(connections::delete);
    }

    private void requireKnownBank(String bankId) {
        if (!parsers.knownBankIds().contains(bankId)) {
            throw new UnknownBankException(bankId, parsers.knownBankIds());
        }
    }
}
