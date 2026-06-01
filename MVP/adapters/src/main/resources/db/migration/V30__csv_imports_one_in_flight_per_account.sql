-- Defence-in-depth for the CSV-import rate limit.
--
-- The app-layer check in CsvImportService.upload() catches "is there a
-- running or recently-completed import for this account?" before INSERTing.
-- But two concurrent upload requests can both pass the check before either
-- INSERTs — race window. This partial unique index closes the race at the
-- schema layer: the second INSERT fails with a UNIQUE constraint violation,
-- which the service catches and re-throws as the standard rate-limited
-- response.
--
-- Only covers PENDING / RUNNING (active) states. The 7-day cooldown on
-- COMPLETED stays app-layer — Postgres doesn't allow non-immutable
-- functions like NOW() in partial-index predicates, so a time-window
-- predicate can't live here.
--
-- Pattern salvaged from the deprecated root project's V11 (job_queue).
-- See Hidden/salvaged-from-deprecated-root.md for the full discussion.

CREATE UNIQUE INDEX idx_csv_imports_one_in_flight_per_account
    ON csv_imports (bank_account_id)
    WHERE status IN ('PENDING', 'RUNNING');
