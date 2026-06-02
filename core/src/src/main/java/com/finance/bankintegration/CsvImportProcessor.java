package com.finance.bankintegration;

import java.util.UUID;

// Async hand-off contract: the upload service persists a PENDING csv_imports
// row, then calls kickoff(importId) and returns immediately. The processor
// implementation drives the row through RUNNING → COMPLETED / FAILED on its
// own thread.
//
// The interface is deliberately one method. Different strategies (batched
// in-process, future worker-pulled, parallel) plug in here without other
// code changing. The csv_imports row IS the contract between the processor
// and the status endpoint — whatever the implementation does, it must keep
// the row updated so a status poll returns useful data.
public interface CsvImportProcessor {

    // Triggers async processing for an already-persisted PENDING import.
    // Returns immediately. Errors during async work are recorded on the
    // csv_imports row (status=FAILED + error_message), not thrown.
    void kickoff(UUID importId);
}
