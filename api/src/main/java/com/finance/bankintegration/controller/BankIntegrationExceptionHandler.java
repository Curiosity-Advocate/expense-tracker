package com.finance.bankintegration.controller;

import com.finance.bankintegration.exception.CsvImportConnectionAlreadyExistsException;
import com.finance.bankintegration.exception.CsvImportNotConfiguredException;
import com.finance.bankintegration.exception.CsvImportRateLimitedException;
import com.finance.bankintegration.exception.UnknownBankException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Bank-integration-specific exception → HTTP translations. Lives inside the
// bankintegration module so the GlobalExceptionHandler stays unaware of
// bankintegration internals (preserving the ArchUnit seal).
//
// Spring discovers all @RestControllerAdvice classes and routes by exception
// type — this advice handles bankintegration exceptions; the global one
// handles everything else.
@RestControllerAdvice
public class BankIntegrationExceptionHandler {

    @ExceptionHandler(CsvImportNotConfiguredException.class)
    public ResponseEntity<?> handleNotConfigured(CsvImportNotConfiguredException ex) {
        return error(HttpStatus.NOT_FOUND, "CSV_IMPORT_NOT_CONFIGURED", ex.getMessage());
    }

    @ExceptionHandler(CsvImportConnectionAlreadyExistsException.class)
    public ResponseEntity<?> handleAlreadyExists(CsvImportConnectionAlreadyExistsException ex) {
        return error(HttpStatus.CONFLICT, "CSV_IMPORT_CONNECTION_EXISTS", ex.getMessage());
    }

    @ExceptionHandler(CsvImportRateLimitedException.class)
    public ResponseEntity<?> handleRateLimited(CsvImportRateLimitedException ex) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("nextAllowedAt", ex.getNextAllowedAt().toString());
        return errorWithExtras(HttpStatus.TOO_MANY_REQUESTS, "CSV_IMPORT_RATE_LIMITED", ex.getMessage(), extra);
    }

    @ExceptionHandler(UnknownBankException.class)
    public ResponseEntity<?> handleUnknownBank(UnknownBankException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_BANK_ID", ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleTooLarge(MaxUploadSizeExceededException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "CSV_IMPORT_FILE_TOO_LARGE",
                "Uploaded file exceeds the 10 MB limit");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return errorWithExtras(status, code, message, Map.of());
    }

    private ResponseEntity<?> errorWithExtras(HttpStatus status, String code, String message,
                                              Map<String, Object> extras) {
        String traceId = MDC.get("traceId");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>();
        body.put("code",      code);
        body.put("message",   message);
        body.put("timestamp", Instant.now().toString());
        body.put("traceId",   traceId);
        body.putAll(extras);
        return ResponseEntity.status(status).body(Map.of("error", body));
    }
}
