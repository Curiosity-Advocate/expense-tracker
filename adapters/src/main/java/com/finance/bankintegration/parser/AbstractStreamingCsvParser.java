package com.finance.bankintegration.parser;

import com.finance.bankintegration.CsvBankParser;
import com.finance.bankintegration.ParseFailureSink;
import com.finance.bankintegration.ParsedCsvRow;
import com.opencsv.CSVReader;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

// Shared scaffolding for streaming per-bank CSV parsers. Owns the iteration
// loop, BOM stripping, blank/header skipping, and resource cleanup so each
// concrete parser only declares the bank-specific bits: the date format and
// the per-row column mapping.
//
// Subclasses implement:
//   - bankId(), validFromDate(), versionTag()        (from CsvBankParser)
//   - dateFormat() / dateFormatHint()                (used by parseDate + header detection)
//   - parseRow(...)                                  (the bank-specific column extraction)
//
// Per-row failures are reported via ParseFailureSink and the row skipped —
// the Stream emits only successes (see CsvBankParser javadoc).
public abstract class AbstractStreamingCsvParser implements CsvBankParser {

    private static final char UTF_BOM = (char) 0xFEFF;

    // Subclass plumbing ──────────────────────────────────────────────────

    protected abstract DateTimeFormatter dateFormat();
    protected abstract String            dateFormatHint();
    protected abstract ParsedCsvRow      parseRow(String[] row, int rowNumber, String rawLine, ParseFailureSink onFailure);

    // Public parse entry point ──────────────────────────────────────────

    @Override
    public final Stream<ParsedCsvRow> parse(Reader csvReader, ParseFailureSink onFailure) {
        CSVReader reader = new CSVReader(stripBom(csvReader));
        Iterator<String[]> rawRows = reader.iterator();
        Iterator<ParsedCsvRow> parsed = new RowIterator(rawRows, onFailure);

        return StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(parsed, Spliterator.ORDERED), false)
                .onClose(() -> {
                    try { reader.close(); }
                    catch (IOException ignore) {
                        // Close-time errors don't roll back parsing that already succeeded.
                        // The Reader is local to this call; nothing else holds a handle.
                    }
                });
    }

    // Inner iterator ─────────────────────────────────────────────────────

    private final class RowIterator implements Iterator<ParsedCsvRow> {
        private final Iterator<String[]> rawRows;
        private final ParseFailureSink   onFailure;
        private int rowNumber = 0;
        private ParsedCsvRow next;

        RowIterator(Iterator<String[]> rawRows, ParseFailureSink onFailure) {
            this.rawRows   = rawRows;
            this.onFailure = onFailure;
            this.next      = advance();
        }

        @Override public boolean hasNext() { return next != null; }
        @Override public ParsedCsvRow next() {
            ParsedCsvRow current = next;
            next = advance();
            return current;
        }

        private ParsedCsvRow advance() {
            while (rawRows.hasNext()) {
                rowNumber++;
                String[] row = rawRows.next();
                if (shouldSkip(row, rowNumber)) continue;
                ParsedCsvRow result = parseRow(row, rowNumber, joinForRecord(row), onFailure);
                if (result != null) return result;
            }
            return null;
        }
    }

    // Skip rules ─────────────────────────────────────────────────────────

    private boolean shouldSkip(String[] row, int rowNumber) {
        if (isAllBlank(row)) return true;
        return rowNumber == 1 && looksLikeHeader(row);
    }

    // True if the first cell is text that doesn't parse as a date. We treat
    // any such row-1 cell as a header line and skip it. Avoids needing the
    // user to know whether their export has headers.
    //
    // Whitespace-suffix tolerance: some banks (Ubank) put "DD/MM/YYYY H:mm"
    // in the date column. We strip anything after the first whitespace
    // before trying to parse, so the date prefix matches dateFormat() even
    // if there's a trailing time component.
    private boolean looksLikeHeader(String[] row) {
        if (row.length < 1) return false;
        String first = row[0].trim();
        if (first.isEmpty()) return false;
        int firstSpace = first.indexOf(' ');
        String datePart = firstSpace > 0 ? first.substring(0, firstSpace) : first;
        try { LocalDate.parse(datePart, dateFormat()); return false; }
        catch (Exception e) { return true; }
    }

    // Shared per-row utilities for subclasses ────────────────────────────

    // Parses the date column. Returns null and reports a failure on error;
    // subclass parseRow() typically calls this first and short-circuits.
    protected LocalDate parseDate(String raw, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        try {
            return LocalDate.parse(raw.trim(), dateFormat());
        } catch (Exception e) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Invalid date '" + raw + "' (expected " + dateFormatHint() + "): " + e.getMessage());
            return null;
        }
    }

    // Parses a required signed-amount column (single-amount banks like CBA / ANZ / AMP).
    protected static BigDecimal parseAmount(String raw, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Invalid amount '" + raw + "': " + e.getMessage());
            return null;
        }
    }

    // Parses an optional decimal column (split-amount banks like Ubank / Qudos / Suncorp).
    // Blank → BigDecimal.ZERO (typical: only one of two amount columns is populated).
    protected static BigDecimal parseOptionalDecimal(String raw, int rowNumber, String rawLine,
                                                     String columnName, ParseFailureSink onFailure) {
        if (raw == null || raw.trim().isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Invalid " + columnName + " amount '" + raw + "': " + e.getMessage());
            return null;
        }
    }

    // Parses a required signed-amount cell that may include "$" and thousands
    // commas. Handles "$50.00", "-$50.00", "$1,234.56".
    protected static BigDecimal parseDollarAmount(String raw, int rowNumber, String rawLine, ParseFailureSink onFailure) {
        try {
            return new BigDecimal(raw.trim().replace("$", "").replace(",", ""));
        } catch (Exception e) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Invalid amount '" + raw + "': " + e.getMessage());
            return null;
        }
    }

    // Optional variant: blank → ZERO. For split-amount banks where each row
    // populates only one of two amount columns.
    protected static BigDecimal parseOptionalDollarAmount(String raw, int rowNumber, String rawLine,
                                                          String columnName, ParseFailureSink onFailure) {
        if (raw == null || raw.trim().isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(raw.trim().replace("$", "").replace(",", ""));
        } catch (Exception e) {
            onFailure.onFailure(rowNumber, rawLine,
                    "Invalid " + columnName + " amount '" + raw + "': " + e.getMessage());
            return null;
        }
    }

    // Stream/CSV plumbing utilities ──────────────────────────────────────

    private static boolean isAllBlank(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    private static String joinForRecord(String[] row) {
        return String.join(",", row);
    }

    // Some bank exports prefix the file with a UTF-8 BOM (U+FEFF). OpenCSV
    // doesn't strip it; without this, the first cell of the first row starts
    // with an invisible character and date parsing fails mysteriously.
    private static Reader stripBom(Reader reader) {
        PushbackReader pr = new PushbackReader(reader);
        try {
            int ch = pr.read();
            if (ch != -1 && ch != UTF_BOM) pr.unread(ch);
            return pr;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read CSV input", e);
        }
    }
}
