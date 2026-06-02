package com.finance.bankintegration.service;

import com.finance.bankintegration.CsvBankParser;
import com.finance.bankintegration.CsvImportProcessor;
import com.finance.bankintegration.CsvImportStatusView;
import com.finance.bankintegration.CsvImportSubmissionResult;
import com.finance.bankintegration.entity.CsvImportConnectionEntity;
import com.finance.bankintegration.entity.CsvImportEntity;
import com.finance.bankintegration.exception.CsvImportNotConfiguredException;
import com.finance.bankintegration.exception.CsvImportRateLimitedException;
import com.finance.bankintegration.repository.CsvImportConnectionRepository;
import com.finance.bankintegration.repository.CsvImportRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// Sync part of the CSV upload path: validate, persist a PENDING csv_imports
// row, hand off to the async processor, return. Operates on the app pool
// with RLS — every read/write is scoped to the authenticated user.
//
// Status reads also live here so the controller has one place to call.
//
// CsvImportNotConfiguredException → 404
// CsvImportRateLimitedException   → 429
@Service
public class CsvImportService {

    private static final long RATE_LIMIT_DAYS = 7;

    private final CsvImportConnectionRepository connections;
    private final CsvImportRepository           imports;
    private final CsvParserRegistry             parsers;
    private final CsvImportProcessor            processor;
    private final Clock                         clock;

    public CsvImportService(CsvImportConnectionRepository connections,
                            CsvImportRepository imports,
                            CsvParserRegistry parsers,
                            CsvImportProcessor processor,
                            Clock clock) {
        this.connections = connections;
        this.imports     = imports;
        this.parsers     = parsers;
        this.processor   = processor;
        this.clock       = clock;
    }

    // Upload entry point. Each step does its own auto-managed transaction
    // (no class-level @Transactional). Reasons:
    //  1. We want the csv_imports INSERT to commit BEFORE we kick off the
    //     async processor, so the async thread always finds the row.
    //  2. Atomicity between rate-limit check and INSERT isn't required at
    //     personal scale (a parallel-upload race would deduplicate later).
    public CsvImportSubmissionResult upload(UUID bankAccountId,
                                             byte[] csvBytes,
                                             LocalDate exportedOnDate,
                                             UUID currentUserId) {

        CsvImportConnectionEntity connection = connections.findById(bankAccountId)
                .orElseThrow(() -> new CsvImportNotConfiguredException(bankAccountId));

        var sevenDaysAgo = clock.instant().minus(RATE_LIMIT_DAYS, ChronoUnit.DAYS);
        if (imports.hasRecentOrInFlightImport(bankAccountId, sevenDaysAgo)) {
            throw new CsvImportRateLimitedException(sevenDaysAgo.plus(RATE_LIMIT_DAYS, ChronoUnit.DAYS));
        }

        CsvBankParser parser = parsers.pickByDate(connection.getBankId(), exportedOnDate);

        CsvImportEntity row = new CsvImportEntity();
        row.setBankAccountId(bankAccountId);
        row.setUserId(currentUserId);
        row.setStatus("PENDING");
        row.setExportedOnDate(exportedOnDate);
        row.setParserVersionTag(parser.versionTag());
        row.setRawCsvBytes(csvBytes);

        CsvImportEntity saved;
        try {
            saved = imports.save(row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Schema-level catch for the partial unique index (V30) on
            // (bank_account_id) WHERE status IN ('PENDING','RUNNING').
            // Fires when two concurrent uploads slip past the app-layer
            // hasRecentOrInFlightImport() check in the same millisecond.
            // Surface as the same 429 the app-layer check would have
            // returned. nextAllowedAt is set to "shortly" since the
            // other in-flight import will finish on its own.
            throw new CsvImportRateLimitedException(clock.instant().plusSeconds(60));
        }

        processor.kickoff(saved.getId());

        return new CsvImportSubmissionResult(
                saved.getId(),
                saved.getParserVersionTag(),
                saved.getExportedOnDate(),
                saved.getSubmittedAt()
        );
    }

    // Status read. Returns the snapshot of one csv_imports row;
    // CsvImportNotConfiguredException with "import not found" if the id is
    // unknown OR hidden by RLS (indistinguishable to avoid enumeration).
    public CsvImportStatusView status(UUID importId) {
        return imports.findById(importId)
                .map(CsvImportService::toView)
                .orElseThrow(() -> new CsvImportNotConfiguredException(importId));
    }

    private static CsvImportStatusView toView(CsvImportEntity e) {
        return new CsvImportStatusView(
                e.getId(),
                e.getBankAccountId(),
                e.getStatus(),
                e.getExportedOnDate(),
                e.getParserVersionTag(),
                e.getImportedCount(),
                e.getDedupedCount(),
                e.getParseErrorCount(),
                e.getLastProcessedRow(),
                e.getErrorMessage(),
                e.getSubmittedAt(),
                e.getStartedAt(),
                e.getCompletedAt()
        );
    }
}
