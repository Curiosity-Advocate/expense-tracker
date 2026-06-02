package com.finance.bankintegration.parser;

import com.finance.bankintegration.ParseFailureSink;
import com.finance.bankintegration.ParsedCsvRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// Commonwealth Bank (CBA) "Transaction history" CSV export.
//
// Format (no header row by default; tolerated if present):
//   col 0: Date            DD/MM/YYYY
//   col 1: Amount          signed decimal (negative = debit, positive = credit)
//   col 2: Description     free text (empty allowed)
//   col 3: Balance         IGNORED (optional)
//
// Verify against a real sample in B1.5.
@Component
public class CbaCsvParserV1 extends AbstractStreamingCsvParser {

    private static final DateTimeFormatter CBA_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MIN_COLUMNS = 3;

    @Override public String    bankId()         { return "cba"; }
    @Override public LocalDate validFromDate()  { return LocalDate.MIN; }
    @Override public String    versionTag()     { return "csv_cba_v1"; }

    @Override protected DateTimeFormatter dateFormat()     { return CBA_DATE; }
    @Override protected String            dateFormatHint() { return "DD/MM/YYYY"; }

    @Override
    protected ParsedCsvRow parseRow(String[] row, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        if (row.length < MIN_COLUMNS) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Expected at least " + MIN_COLUMNS + " columns (Date, Amount, Description), got " + row.length);
            return null;
        }

        LocalDate date = parseDate(row[0], rowNumber, rawLine, onFailure);
        if (date == null) return null;

        BigDecimal amount = parseAmount(row[1], rowNumber, rawLine, onFailure);
        if (amount == null) return null;

        return new ParsedCsvRow(date, amount, row[2].trim(), rawLine);
    }
}
