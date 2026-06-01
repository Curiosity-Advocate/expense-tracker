package com.finance.bankintegration.parser;

import com.finance.bankintegration.ParseFailureSink;
import com.finance.bankintegration.ParsedCsvRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Ubank CSV export (format verified from 2026-05-31 sample).
//
// HAS a header row:
//   "Date and time,Description,Debit,Credit,From account,To account,
//    Payment type,Category,Receipt number,Transaction ID"
//
// Data row columns:
//   col 0: Date and time   "DD/MM/YYYY H:mm" (24h, may be 1- or 2-digit hour)
//   col 1: Description     free text (sometimes leading space)
//   col 2: Debit           "$X.XX" or blank
//   col 3: Credit          "$X.XX" or blank
//   col 4: From account
//   col 5: To account
//   col 6: Payment type
//   col 7: Category
//   col 8: Receipt number
//   col 9: Transaction ID  ← stable per-transaction reference; currently
//                            unused (we compute our own fingerprint).
//                            Future B1.7 optimisation: use this as
//                            external_transaction_id for better re-import
//                            dedup, namespaced as "ubank:<id>".
//
// Sign normalisation: amount = credit - debit. Both columns are positive
// "$X.XX" or blank.
@Component
public class UbankCsvParserV1 extends AbstractStreamingCsvParser {

    private static final DateTimeFormatter UBANK_DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm");
    private static final DateTimeFormatter UBANK_DATE     = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MIN_COLUMNS = 4;

    @Override public String    bankId()         { return "ubank"; }
    @Override public LocalDate validFromDate()  { return LocalDate.MIN; }
    @Override public String    versionTag()     { return "csv_ubank_v1"; }

    @Override protected DateTimeFormatter dateFormat()     { return UBANK_DATE; }
    @Override protected String            dateFormatHint() { return "DD/MM/YYYY H:mm"; }

    @Override
    protected ParsedCsvRow parseRow(String[] row, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        if (row.length < MIN_COLUMNS) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Expected at least " + MIN_COLUMNS + " columns (Date and time, Description, Debit, Credit), got " + row.length);
            return null;
        }

        LocalDate date = parseUbankDate(row[0], rowNumber, rawLine, onFailure);
        if (date == null) return null;

        BigDecimal debit  = parseOptionalDollarAmount(row[2], rowNumber, rawLine, "Debit",  onFailure);
        if (debit == null) return null;

        BigDecimal credit = parseOptionalDollarAmount(row[3], rowNumber, rawLine, "Credit", onFailure);
        if (credit == null) return null;

        return new ParsedCsvRow(date, credit.subtract(debit), row[1].trim(), rawLine);
    }

    // Strict format is "DD/MM/YYYY H:mm" — try that first, fall back to
    // date-only in case Ubank ever drops the time component on some rows.
    // Returns the date portion only; time is discarded.
    private static LocalDate parseUbankDate(String raw, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        String trimmed = raw.trim();
        try {
            return LocalDateTime.parse(trimmed, UBANK_DATETIME).toLocalDate();
        } catch (Exception withTime) {
            try {
                return LocalDate.parse(trimmed, UBANK_DATE);
            } catch (Exception withoutTime) {
                onFailure.onFailure(rowNumber, rawLine,
                        "Invalid date '" + raw + "' (expected DD/MM/YYYY H:mm): " + withTime.getMessage());
                return null;
            }
        }
    }
}
