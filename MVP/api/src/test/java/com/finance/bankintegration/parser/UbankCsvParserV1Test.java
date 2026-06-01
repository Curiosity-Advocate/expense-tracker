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

// Unit tests for UbankCsvParserV1 using the verified 2026-05-31 sample format.
class UbankCsvParserV1Test {

    private final UbankCsvParserV1 parser = new UbankCsvParserV1();

    @Test
    void parsesSampleWithHeaderAndTimedDate() {
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseResource("/csv-samples/ubank-sample.csv", failures);

        assertThat(failures).as("no parse failures expected").isEmpty();
        // Header row + 3 data rows. The header is skipped; 3 rows parsed.
        assertThat(rows).hasSize(3);

        // Real sample row: "31/05/2026 8:18, Paymet to me,$50.00,,..."
        // $50 in Debit + blank Credit → amount = 0 - 50 = -50
        ParsedCsvRow first = rows.get(0);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("-50.00"));
        assertThat(first.description()).isEqualTo("Paymet to me");

        // Fabricated debit
        ParsedCsvRow coffee = rows.get(1);
        assertThat(coffee.date()).isEqualTo(LocalDate.of(2026, 5, 30));
        assertThat(coffee.amount()).isEqualByComparingTo(new BigDecimal("-5.50"));

        // Fabricated credit
        ParsedCsvRow refund = rows.get(2);
        assertThat(refund.date()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertThat(refund.amount()).isEqualByComparingTo(new BigDecimal("15.00"));
    }

    @Test
    void handlesQuotedTransactionId() {
        // Quoted fields with embedded characters — verify OpenCSV handles them.
        String csv = "Date and time,Description,Debit,Credit,From account,To account,Payment type,Category,Receipt number,Transaction ID\n"
                + "31/05/2026 8:18,Test desc,$10.00,,,,Card,Food,\"REC\",\"TXN-with-special-chars-12345\"\n";
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseString(csv, failures);

        assertThat(failures).isEmpty();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).amount()).isEqualByComparingTo(new BigDecimal("-10.00"));
    }

    @Test
    void rejectsRowWithMalformedAmount() {
        String csv = "Date and time,Description,Debit,Credit,From account,To account,Payment type,Category,Receipt number,Transaction ID\n"
                + "31/05/2026 8:18,Bad row,not-a-number,,,,Card,Food,REC,TXN\n";
        List<RowFailure> failures = new ArrayList<>();
        List<ParsedCsvRow> rows = parseString(csv, failures);

        assertThat(rows).isEmpty();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).message()).contains("Invalid Debit amount");
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
