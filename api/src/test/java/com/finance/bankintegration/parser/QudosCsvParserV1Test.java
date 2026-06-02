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

// Unit tests for QudosCsvParserV1 using the verified 2026-05-31 sample format.
// Covers the two-date-column fallback (Effective Date can be blank, fall
// back to Entered Date — and vice versa).
class QudosCsvParserV1Test {

    private final QudosCsvParserV1 parser = new QudosCsvParserV1();

    @Test
    void parsesSampleWithHeaderAndDualDates() {
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseResource("/csv-samples/qudos-sample.csv", failures);

        assertThat(failures).as("no parse failures expected").isEmpty();
        // Header + 3 data rows → 3 parsed.
        assertThat(rows).hasSize(3);

        // Real row: blank Effective Date, Entered Date 31/05/2026 → uses Entered Date
        ParsedCsvRow osko = rows.get(0);
        assertThat(osko.date()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(osko.amount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(osko.description()).contains("OSKO");

        // Fabricated debit: both dates populated → uses Effective Date
        ParsedCsvRow coffee = rows.get(1);
        assertThat(coffee.date()).isEqualTo(LocalDate.of(2026, 5, 28));
        assertThat(coffee.amount()).isEqualByComparingTo(new BigDecimal("-5.50"));

        // Fabricated: Effective Date populated, Entered Date blank → uses Effective Date
        ParsedCsvRow elec = rows.get(2);
        assertThat(elec.date()).isEqualTo(LocalDate.of(2026, 5, 27));
        assertThat(elec.amount()).isEqualByComparingTo(new BigDecimal("-120.00"));
    }

    @Test
    void rejectsRowWithBothDatesBlank() {
        String csv = "Effective Date,Entered Date,Transaction Description,Amount,Balance\n"
                + ",,Missing both dates,$10.00,-$500\n";
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseString(csv, failures);

        assertThat(rows).isEmpty();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).message()).contains("Invalid date");
    }

    @Test
    void handlesDollarPrefixAndNegativeAmounts() {
        String csv = "Effective Date,Entered Date,Transaction Description,Amount,Balance\n"
                + "01/06/2026,01/06/2026,Big credit,$1234.56,$5000.00\n"
                + "01/06/2026,01/06/2026,Big debit,-$1234.56,$3765.44\n";
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseString(csv, failures);

        assertThat(failures).isEmpty();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).amount()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(rows.get(1).amount()).isEqualByComparingTo(new BigDecimal("-1234.56"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private record RowFailure(int rowNumber, String rawLine, String message) {}

    private List<ParsedCsvRow> parseResource(String classpath, List<RowFailure> failures) {
        try {
            Reader r = new InputStreamReader(new ClassPathResource(classpath).getInputStream(), StandardCharsets.UTF_8);
            try (Stream<ParsedCsvRow> stream = parser.parse(r, sink(failures))) {
                return stream.toList();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<ParsedCsvRow> parseString(String csv, List<RowFailure> failures) {
        Reader r = new java.io.StringReader(csv);
        try (Stream<ParsedCsvRow> stream = parser.parse(r, sink(failures))) {
            return stream.toList();
        }
    }

    private ParseFailureSink sink(List<RowFailure> failures) {
        return (rowNum, raw, msg) -> failures.add(new RowFailure(rowNum, raw, msg));
    }
}
