package com.finance.bankintegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finance.bankintegration.CsvBankParser;
import com.finance.bankintegration.CsvImportProcessor;
import com.finance.bankintegration.ParseFailureSink;
import com.finance.bankintegration.ParsedCsvRow;
import com.finance.bankintegration.TransactionFingerprint;
import com.finance.bankintegration.config.AsyncExecutorConfig;
import com.finance.bankintegration.config.BankIntegrationProperties;
import com.finance.bankintegration.repository.DeadLetterRepository;
import com.finance.bankintegration.repository.RawBankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

// The CSV import worker. Runs on the csvImportExecutor thread pool —
// kickoff() returns immediately to the caller.
//
// Lifecycle of one import:
//   1. Read metadata (user_id, bytes, parser_version_tag) via setup pool —
//      no user context exists yet on this thread, so we bypass RLS.
//   2. Flip row to RUNNING, reset counters.
//   3. For each batch (N clean rows, default 100), open an app-pool tx,
//      SET LOCAL app.current_user_id, insert raw_bank_transactions rows,
//      insert dead_letters rows for parse failures, update counters.
//   4. After the last batch, open a completion tx: status=COMPLETED, clear
//      bytes, and update csv_import_connections.last_imported_at / last_date_to
//      if at least one row was imported (empty-CSV decision: don't bump
//      the 7-day timer for nothing).
//
// Failure modes:
//   - Per-row parse failure → dead_letters row, processing continues.
//   - Per-row insert collision (already-imported transaction) → counted as
//     deduped, processing continues.
//   - Terminal exception (parser blow-up, repeated DB error) → markFailed()
//     records the message, clears bytes, leaves connection.last_* alone.
//   - JVM crash mid-import → CsvImportStartupRecovery resets RUNNING rows
//     past staleness threshold to PENDING and re-kicks-off; dedup handles
//     partial inserts from the previous run.
@Component
public class AsyncBatchedCsvImportProcessor implements CsvImportProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncBatchedCsvImportProcessor.class);

    // Setup pool — BYPASSRLS — used for the initial metadata read and for
    // the terminal markFailed() path (which may run with no user_id known).
    private final NamedParameterJdbcTemplate     setupJdbc;
    // App pool — RLS-enforced — used for everything once user_id is known.
    private final NamedParameterJdbcTemplate     appJdbc;
    private final TransactionTemplate            appTx;
    private final CsvParserRegistry              parsers;
    private final RawBankTransactionRepository   rawRepo;
    private final DeadLetterRepository           deadLetterRepo;
    private final ObjectMapper                   objectMapper;
    private final BankIntegrationProperties      props;

    public AsyncBatchedCsvImportProcessor(
            @org.springframework.beans.factory.annotation.Qualifier("setupJdbcTemplate") NamedParameterJdbcTemplate setupJdbc,
            NamedParameterJdbcTemplate appJdbc,
            @org.springframework.beans.factory.annotation.Qualifier("appTransactionManager")
                org.springframework.transaction.PlatformTransactionManager appTxManager,
            CsvParserRegistry parsers,
            RawBankTransactionRepository rawRepo,
            DeadLetterRepository deadLetterRepo,
            ObjectMapper objectMapper,
            BankIntegrationProperties props) {
        this.setupJdbc      = setupJdbc;
        this.appJdbc        = appJdbc;
        this.appTx          = new TransactionTemplate(appTxManager);
        this.parsers        = parsers;
        this.rawRepo        = rawRepo;
        this.deadLetterRepo = deadLetterRepo;
        this.objectMapper   = objectMapper;
        this.props          = props;
    }

    @Async(AsyncExecutorConfig.CSV_IMPORT_EXECUTOR)
    @Override
    public void kickoff(UUID importId) {
        ImportContext ctx;
        try {
            ctx = loadAndMarkRunning(importId);
        } catch (Exception e) {
            LOG.error("CSV import {} failed at startup", importId, e);
            markFailed(importId, "Initial load failed: " + e.getMessage());
            return;
        }

        try {
            processBatches(ctx);
            complete(ctx);
        } catch (Exception e) {
            LOG.error("CSV import {} failed during processing", importId, e);
            markFailed(importId, "Processing failed: " + e.getMessage());
        }
    }

    // ── PENDING → RUNNING ───────────────────────────────────────────────────

    private ImportContext loadAndMarkRunning(UUID importId) {
        var params = new MapSqlParameterSource("id", importId);
        ImportContext ctx = setupJdbc.queryForObject("""
                SELECT id, user_id, bank_account_id, raw_csv_bytes,
                       parser_version_tag, exported_on_date
                  FROM csv_imports
                 WHERE id = :id
                """,
                params,
                (rs, n) -> new ImportContext(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("user_id")),
                        UUID.fromString(rs.getString("bank_account_id")),
                        rs.getBytes("raw_csv_bytes"),
                        rs.getString("parser_version_tag"),
                        rs.getObject("exported_on_date", LocalDate.class)
                ));

        // Reset counters in case this is a recovery retry.
        setupJdbc.update("""
                UPDATE csv_imports
                   SET status = 'RUNNING',
                       started_at = NOW(),
                       imported_count = 0,
                       deduped_count = 0,
                       parse_error_count = 0,
                       last_processed_row = 0,
                       error_message = NULL
                 WHERE id = :id
                """, params);

        return ctx;
    }

    // ── Batch loop ──────────────────────────────────────────────────────────

    private void processBatches(ImportContext ctx) {
        CsvBankParser parser = parsers.findByVersionTag(ctx.parserVersionTag());
        int batchSize = props.csv().batchSize();

        Counters counters = new Counters();
        List<ParsedCsvRow>   cleanBatch   = new ArrayList<>(batchSize);
        List<RowFailure>     failureBatch = new ArrayList<>();
        // Last row number observed (clean or failure). Used by lastProcessedRow.
        int[] lastRowNumberSeen = { 0 };

        ParseFailureSink sink = (rowNumber, rawLine, message) -> {
            failureBatch.add(new RowFailure(rowNumber, rawLine, message));
            lastRowNumberSeen[0] = Math.max(lastRowNumberSeen[0], rowNumber);
        };

        // Reader is not in try-with-resources because the parser's Stream
        // owns it (via Stream.onClose()) and closing it twice surfaces a
        // checked IOException from Reader.close() that the compiler can't
        // see is benign.
        Reader reader = new InputStreamReader(new ByteArrayInputStream(ctx.rawCsvBytes()), StandardCharsets.UTF_8);
        try (Stream<ParsedCsvRow> stream = parser.parse(reader, sink)) {

            Iterator<ParsedCsvRow> it = stream.iterator();
            int rowOrdinal = 0;
            while (it.hasNext()) {
                ParsedCsvRow row = it.next();
                cleanBatch.add(row);
                rowOrdinal++;
                lastRowNumberSeen[0] = Math.max(lastRowNumberSeen[0], rowOrdinal);
                if (cleanBatch.size() + failureBatch.size() >= batchSize) {
                    persistBatch(ctx, cleanBatch, failureBatch, counters, lastRowNumberSeen[0]);
                    cleanBatch.clear();
                    failureBatch.clear();
                }
            }
        }

        // Final partial batch (may also be empty if file had only failures
        // accumulating just past the last commit, or no rows at all).
        if (!cleanBatch.isEmpty() || !failureBatch.isEmpty()) {
            persistBatch(ctx, cleanBatch, failureBatch, counters, lastRowNumberSeen[0]);
        }

        ctx.runningCounters = counters;
    }

    private void persistBatch(ImportContext ctx,
                              List<ParsedCsvRow> cleanRows,
                              List<RowFailure>   failures,
                              Counters           counters,
                              int                lastRowNumber) {
        appTx.executeWithoutResult(status -> {
            setRlsContext(ctx.userId());

            for (ParsedCsvRow row : cleanRows) {
                String externalId = TransactionFingerprint.compute(
                        row.date(), row.amount(), row.description(), ctx.bankAccountId());
                String payloadJson = buildRawPayload(row);

                boolean inserted = rawRepo.insertIfNew(
                        UUID.randomUUID(),
                        ctx.userId(),
                        ctx.parserVersionTag(),
                        externalId,
                        payloadJson);

                if (inserted) {
                    counters.imported++;
                    if (counters.maxDate == null || row.date().isAfter(counters.maxDate)) {
                        counters.maxDate = row.date();
                    }
                } else {
                    counters.deduped++;
                }
            }

            for (RowFailure f : failures) {
                deadLetterRepo.insert(
                        UUID.randomUUID(),
                        ctx.userId(),
                        "CSV_IMPORT",
                        buildFailurePayload(ctx, f),
                        "com.finance.bankintegration.exception.CsvRowParseFailure",
                        f.message());
                counters.parseErrors++;
            }

            appJdbc.update("""
                    UPDATE csv_imports
                       SET imported_count    = :imported,
                           deduped_count     = :deduped,
                           parse_error_count = :parseErrors,
                           last_processed_row = :lastRow
                     WHERE id = :id
                    """,
                    new MapSqlParameterSource()
                            .addValue("imported",    counters.imported)
                            .addValue("deduped",     counters.deduped)
                            .addValue("parseErrors", counters.parseErrors)
                            .addValue("lastRow",     lastRowNumber)
                            .addValue("id",          ctx.importId()));
        });
    }

    // ── Completion ──────────────────────────────────────────────────────────

    private void complete(ImportContext ctx) {
        appTx.executeWithoutResult(status -> {
            setRlsContext(ctx.userId());

            // Flip csv_imports to COMPLETED + clear bytes.
            appJdbc.update("""
                    UPDATE csv_imports
                       SET status                   = 'COMPLETED',
                           completed_at             = NOW(),
                           raw_csv_bytes            = '\\x'::bytea,
                           raw_csv_bytes_deleted_at = NOW()
                     WHERE id = :id
                    """, new MapSqlParameterSource("id", ctx.importId()));

            // Empty-CSV / all-failures decision: don't update connection.last_*
            // if no rows were imported. The 7-day rate-limit window stays open.
            if (ctx.runningCounters.imported > 0) {
                appJdbc.update("""
                        UPDATE csv_import_connections
                           SET last_imported_at = NOW(),
                               last_date_to     = :lastDate
                         WHERE bank_account_id = :bankAccountId
                        """,
                        new MapSqlParameterSource()
                                .addValue("lastDate",      ctx.runningCounters.maxDate)
                                .addValue("bankAccountId", ctx.bankAccountId()));
            }
        });
        LOG.info("CSV import {} completed: imported={} deduped={} parseErrors={}",
                ctx.importId(), ctx.runningCounters.imported,
                ctx.runningCounters.deduped, ctx.runningCounters.parseErrors);
    }

    // ── Terminal failure ────────────────────────────────────────────────────

    private void markFailed(UUID importId, String message) {
        // Setup pool: no user context required, and we may not even know it.
        setupJdbc.update("""
                UPDATE csv_imports
                   SET status                   = 'FAILED',
                       completed_at             = NOW(),
                       error_message            = :msg,
                       raw_csv_bytes            = '\\x'::bytea,
                       raw_csv_bytes_deleted_at = NOW()
                 WHERE id = :id
                   AND status IN ('PENDING', 'RUNNING')
                """,
                new MapSqlParameterSource()
                        .addValue("id",  importId)
                        .addValue("msg", message));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void setRlsContext(UUID userId) {
        appJdbc.queryForObject(
                "SELECT set_config('app.current_user_id', :uid, true)",
                new MapSqlParameterSource("uid", userId.toString()),
                String.class);
    }

    private String buildRawPayload(ParsedCsvRow row) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode parsed = root.putObject("parsed");
        parsed.put("date",        row.date().toString());
        parsed.put("amount",      row.amount().toPlainString());
        parsed.put("description", row.description());
        root.put("raw_line", row.rawLine());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise raw_payload", e);
        }
    }

    private String buildFailurePayload(ImportContext ctx, RowFailure f) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("bankAccountId",     ctx.bankAccountId().toString());
        root.put("importId",          ctx.importId().toString());
        root.put("exportedOnDate",    ctx.exportedOnDate().toString());
        root.put("parserVersionTag",  ctx.parserVersionTag());
        root.put("rowNumber",         f.rowNumber());
        root.put("rawLine",           f.rawLine());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise failure payload", e);
        }
    }

    // ── Internal records ────────────────────────────────────────────────────

    private static final class ImportContext {
        private final UUID      importId;
        private final UUID      userId;
        private final UUID      bankAccountId;
        private final byte[]    rawCsvBytes;
        private final String    parserVersionTag;
        private final LocalDate exportedOnDate;
        // Set after processBatches() runs; read by complete().
        private Counters runningCounters = new Counters();

        ImportContext(UUID importId, UUID userId, UUID bankAccountId, byte[] rawCsvBytes,
                       String parserVersionTag, LocalDate exportedOnDate) {
            this.importId         = importId;
            this.userId           = userId;
            this.bankAccountId    = bankAccountId;
            this.rawCsvBytes      = rawCsvBytes;
            this.parserVersionTag = parserVersionTag;
            this.exportedOnDate   = exportedOnDate;
        }

        UUID      importId()         { return importId; }
        UUID      userId()           { return userId; }
        UUID      bankAccountId()    { return bankAccountId; }
        byte[]    rawCsvBytes()      { return rawCsvBytes; }
        String    parserVersionTag() { return parserVersionTag; }
        LocalDate exportedOnDate()   { return exportedOnDate; }
    }

    private static final class Counters {
        int imported    = 0;
        int deduped     = 0;
        int parseErrors = 0;
        LocalDate maxDate = null;
    }

    private record RowFailure(int rowNumber, String rawLine, String message) {}
}
