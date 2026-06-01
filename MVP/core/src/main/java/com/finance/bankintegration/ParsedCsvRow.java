package com.finance.bankintegration;

import java.math.BigDecimal;
import java.time.LocalDate;

// One transaction row as a CsvBankParser extracted it from a bank's CSV
// export. Source-format-agnostic at this point — every parser normalises
// to this shape before handing rows back to the import service.
//
// Sign convention: amount is signed. Negative = money out (expense / debit),
// positive = money in (income / refund / credit-card payment). Parsers
// that read split debit/credit columns combine them into one signed value
// here. This convention is what B3's normaliser later reads to decide
// "is this an expense?" — keeping it consistent across all parsers is
// a hard contract.
//
// rawLine is the verbatim CSV line text, preserved so we can write it
// into raw_bank_transactions.raw_payload alongside the parsed view (and
// into dead_letters when parsing the SAME line fails downstream).
public record ParsedCsvRow(
        LocalDate   date,
        BigDecimal  amount,
        String      description,
        String      rawLine
) {
}
