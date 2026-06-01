package com.finance.bankintegration.parser;

import com.finance.bankintegration.ParseFailureSink;
import com.finance.bankintegration.ParsedCsvRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// Suncorp Bank CSV export.
//
// Format assumed (split withdrawals/deposits columns; no header):
//   col 0: Date            DD/MM/YYYY
//   col 1: Description     free text
//   col 2: Withdrawals     blank or positive decimal
//   col 3: Deposits        blank or positive decimal
//   col 4: Balance         IGNORED (optional)
//
// Sign normalisation: amount = deposits - withdrawals.
// Verify against a real sample in B1.5.
@Component
public class SuncorpCsvParserV1 extends AbstractStreamingCsvParser {

    private static final DateTimeFormatter SUNCORP_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MIN_COLUMNS = 4;

    @Override public String    bankId()         { return "suncorp"; }
    @Override public LocalDate validFromDate()  { return LocalDate.MIN; }
    @Override public String    versionTag()     { return "csv_suncorp_v1"; }

    @Override protected DateTimeFormatter dateFormat()     { return SUNCORP_DATE; }
    @Override protected String            dateFormatHint() { return "DD/MM/YYYY"; }

    @Override
    protected ParsedCsvRow parseRow(String[] row, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        if (row.length < MIN_COLUMNS) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Expected at least " + MIN_COLUMNS + " columns (Date, Description, Withdrawals, Deposits), got " + row.length);
            return null;
        }

        LocalDate date = parseDate(row[0], rowNumber, rawLine, onFailure);
        if (date == null) return null;

        BigDecimal withdrawals = parseOptionalDecimal(row[2], rowNumber, rawLine, "Withdrawals", onFailure);
        if (withdrawals == null) return null;

        BigDecimal deposits    = parseOptionalDecimal(row[3], rowNumber, rawLine, "Deposits",    onFailure);
        if (deposits == null) return null;

        return new ParsedCsvRow(date, deposits.subtract(withdrawals), row[1].trim(), rawLine);
    }
}
