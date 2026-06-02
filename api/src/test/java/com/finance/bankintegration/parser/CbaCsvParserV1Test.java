package com.finance.bankintegration.parser;

import com.finance.bankintegration.ParseFailureSink;
import com.finance.bankintegration.ParsedCsvRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// Unit tests for CbaCsvParserV1. Uses the real sample at
// /csv-samples/cba-sample.csv plus a couple of fabricated additional rows
// to cover the credit case (the real-world sample is a debit only).
class CbaCsvParserV1Test {

    private final CbaCsvParserV1 parser = new CbaCsvParserV1();

    @Test
    void parsesAllThreeSampleRows() {
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseResource("/csv-samples/cba-sample.csv", failures);

        assertThat(failures).as("no parse failures expected").isEmpty();
        assertThat(rows).hasSize(3);

        ParsedCsvRow debit = rows.get(0);
        assertThat(debit.date()).isEqualTo(LocalDate.of(2025, 7, 7));
        assertThat(debit.amount()).isEqualByComparingTo(new BigDecimal("-500"));
        assertThat(debit.description()).isEqualTo("Transfer to xxx567 from CBA app");

        ParsedCsvRow credit = rows.get(1);
        assertThat(credit.date()).isEqualTo(LocalDate.of(2025, 7, 5));
        assertThat(credit.amount()).isEqualByComparingTo(new BigDecimal("1500.50"));
        assertThat(credit.description()).contains("Salary deposit");

        ParsedCsvRow groceries = rows.get(2);
        assertThat(groceries.amount()).isEqualByComparingTo(new BigDecimal("-42.50"));
    }

    @Test
    void reportsParseFailureForInvalidDate() {
        String csv = "not-a-date,-50,Bad row,100\n07/07/2025,-100,Good row,200\n";
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseString(csv, failures);

        assertThat(rows).hasSize(1).extracting(ParsedCsvRow::description).containsExactly("Good row");
        assertThat(failures).hasSize(1).first().satisfies(f -> {
            assertThat(f.message()).contains("Invalid date");
            assertThat(f.rowNumber()).isEqualTo(1);
        });
    }

    @Test
    void tolaratesHeaderRow() {
        String csv = "Date,Amount,Description,Balance\n07/07/2025,-500,Transfer,300\n";
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseString(csv, failures);

        assertThat(failures).isEmpty();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("Transfer");
    }

    @Test
    void acceptsEmptyDescription() {
        String csv = "07/07/2025,-500,,300\n";
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseString(csv, failures);

        assertThat(failures).isEmpty();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private record RowFailure(int rowNumber, String rawLine, String message) {}

    private List<ParsedCsvRow> parseResource(String classpath, List<RowFailure> failures) {
        try (Reader r = new InputStreamReader(new ClassPathResource(classpath).getInputStream(), StandardCharsets.UTF_8);
             Stream<ParsedCsvRow> stream = parser.parse(r, sink(failures))) {
            return stream.toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<ParsedCsvRow> parseString(String csv, List<RowFailure> failures) {
        try (Reader r = new java.io.StringReader(csv);
             Stream<ParsedCsvRow> stream = parser.parse(r, sink(failures))) {
            return stream.toList();
        }
    }

    private ParseFailureSink sink(List<RowFailure> failures) {
        return (rowNum, raw, msg) -> failures.add(new RowFailure(rowNum, raw, msg));
    }
}
