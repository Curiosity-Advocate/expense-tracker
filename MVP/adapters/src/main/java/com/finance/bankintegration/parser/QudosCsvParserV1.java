package com.finance.bankintegration.parser;

import com.finance.bankintegration.ParseFailureSink;
import com.finance.bankintegration.ParsedCsvRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// Qudos Bank CSV export (format verified from 2026-05-31 sample).
//
// HAS a header row:
//   "Effective Date,Entered Date,Transaction Description,Amount,Balance"
//
// Data row columns:
//   col 0: Effective Date  DD/MM/YYYY  (CAN BE BLANK — fall back to col 1)
//   col 1: Entered Date    DD/MM/YYYY
//   col 2: Transaction Description  free text
//   col 3: Amount          "$X.XX" or "-$X.XX" (signed, with $ prefix)
//   col 4: Balance         "$X.XX" or "-$X.XX"  IGNORED
//
// Sign convention: Qudos's Amount is already signed; we just strip the $.
//   "$50.00"  → 50.00  (credit)
//   "-$50.00" → -50.00 (debit)
@Component
public class QudosCsvParserV1 extends AbstractStreamingCsvParser {

    private static final DateTimeFormatter QUDOS_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MIN_COLUMNS = 4;

    @Override public String    bankId()         { return "qudos"; }
    @Override public LocalDate validFromDate()  { return LocalDate.MIN; }
    @Override public String    versionTag()     { return "csv_qudos_v1"; }

    @Override protected DateTimeFormatter dateFormat()     { return QUDOS_DATE; }
    @Override protected String            dateFormatHint() { return "DD/MM/YYYY"; }

    @Override
    protected ParsedCsvRow parseRow(String[] row, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        if (row.length < MIN_COLUMNS) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Expected at least " + MIN_COLUMNS + " columns (Effective Date, Entered Date, Description, Amount), got " + row.length);
            return null;
        }

        // Effective Date can be blank — fall back to Entered Date.
        String dateCell = row[0].trim().isEmpty() ? row[1] : row[0];
        LocalDate date = parseDate(dateCell, rowNumber, rawLine, onFailure);
        if (date == null) return null;

        BigDecimal amount = parseDollarAmount(row[3], rowNumber, rawLine, onFailure);
        if (amount == null) return null;

        return new ParsedCsvRow(date, amount, row[2].trim(), rawLine);
    }
}
