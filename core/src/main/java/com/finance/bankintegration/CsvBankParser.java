package com.finance.bankintegration;

import java.io.Reader;
import java.time.LocalDate;
import java.util.stream.Stream;

// One implementation per (bank, format-version). The import service picks
// the right one at upload time using (bankId, exportedOnDate) — see
// ADR-0020 for the date-dispatched model.
//
// When a bank changes their CSV export format, we ship a new parser with
// the same bankId() but a later validFromDate() and a new versionTag().
// Existing connections need no change — older exports still parse with
// the older parser; newer exports use the new one.
public interface CsvBankParser {

    // Bank identifier matching csv_import_connections.bank_id values.
    // Lowercase. e.g. "cba", "anz", "ubank".
    String bankId();

    // Earliest export date this parser handles. The dispatcher picks the
    // parser whose validFromDate is the latest one <= the upload's
    // exportedOnDate for this bankId. For v1 parsers this is LocalDate.MIN
    // (handles everything until a v2 ships).
    LocalDate validFromDate();

    // Stamped into raw_bank_transactions.source_format on each persisted
    // row, so per-row provenance survives independently of which parser
    // is currently registered. e.g. "csv_cba_v1".
    String versionTag();

    // Stream the parsed rows. Implementations consume the Reader lazily
    // so a large CSV doesn't load fully into memory. The caller closes
    // the Reader.
    //
    // Per-row failures: implementations report bad rows via `onFailure`
    // and skip them — the returned Stream contains only successfully
    // parsed rows. The service feeds onFailure invocations into the
    // dead_letters table, one row per call.
    //
    // Terminal failures (unrecognised header, completely wrong format,
    // I/O error mid-read): throw a plain exception. The service catches
    // it, writes a SINGLE dead_letter capturing the import attempt, and
    // aborts the whole import.
    Stream<ParsedCsvRow> parse(Reader csvReader, ParseFailureSink onFailure);
}
