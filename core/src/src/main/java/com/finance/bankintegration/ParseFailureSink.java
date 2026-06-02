package com.finance.bankintegration;

// Callback a CsvBankParser invokes for per-row failures. Lets the parser
// emit a clean Stream<ParsedCsvRow> of successes while reporting bad rows
// out-of-band to the service, which writes them to dead_letters.
//
// Why a callback rather than a sum type in the Stream: the consuming
// service treats successes and failures differently (one persists,
// the other writes a dead-letter). Branching at the call site for every
// element would be more boilerplate than a side-channel callback, and
// failures are uncommon — the hot path stays clean.
@FunctionalInterface
public interface ParseFailureSink {

    // Called once per row that failed to parse. rowNumber is 1-indexed
    // by physical CSV line; rawLine is the verbatim source text for
    // operator forensics; message describes what went wrong.
    void onFailure(int rowNumber, String rawLine, String message);
}
