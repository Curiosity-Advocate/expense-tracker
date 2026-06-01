package com.finance.bankintegration.service;

import com.finance.bankintegration.CsvBankParser;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

// Central registry of CsvBankParser beans. Two lookup modes:
//   pickByDate  — used by the upload service: "for bank X, what parser
//                 handles an export from date Y?" Picks the parser whose
//                 validFromDate is the latest one <= the export date.
//   findByVersionTag — used by the async processor: "given the versionTag
//                 the service stamped on csv_imports, give me that parser."
//
// Spring injects every CsvBankParser @Component into the List<> on
// construction, so adding a new parser class is the only change needed
// to wire a new bank.
@Component
public class CsvParserRegistry {

    private final List<CsvBankParser> parsers;

    public CsvParserRegistry(List<CsvBankParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public CsvBankParser pickByDate(String bankId, LocalDate exportedOnDate) {
        return parsers.stream()
                .filter(p -> p.bankId().equals(bankId))
                .filter(p -> !exportedOnDate.isBefore(p.validFromDate()))
                .max(Comparator.comparing(CsvBankParser::validFromDate))
                .orElseThrow(() -> new NoSuchElementException(
                        "No CSV parser registered for bankId=" + bankId
                                + " with validFromDate <= " + exportedOnDate));
    }

    public CsvBankParser findByVersionTag(String versionTag) {
        return parsers.stream()
                .filter(p -> p.versionTag().equals(versionTag))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "No CSV parser registered with versionTag=" + versionTag));
    }

    // The set of bankIds covered by any registered parser. Used by the
    // connection service to pre-validate POST/PATCH inputs and return a
    // friendly error instead of letting the DB CHECK constraint blow up.
    public Set<String> knownBankIds() {
        return parsers.stream().map(CsvBankParser::bankId).collect(Collectors.toUnmodifiableSet());
    }
}
