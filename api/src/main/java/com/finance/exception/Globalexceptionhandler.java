package com.finance.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// @RestControllerAdvice intercepts exceptions thrown from any @RestController.
// This is where exceptions become the error envelope shape defined in api_design.md:
// { "error": { "code": ..., "message": ..., "timestamp": ..., "traceId": ... } }
//
// traceId here is a placeholder UUID — the real traceId will be injected by the
// gateway filter
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<?> handleUserAlreadyExists() {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(errorEnvelope("USER_ALREADY_EXISTS", "Username or email already in use"));
    }

    // 423 Locked — more precise than 401 for a lockout scenario.
    // lockedUntil tells the client exactly when to retry — prevents hammering the endpoint.
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<?> handleAccountLocked(AccountLockedException ex) {
        return ResponseEntity
                .status(HttpStatus.LOCKED)
                .body(errorEnvelope("ACCOUNT_LOCKED",
                        "Account temporarily locked. Try again after " + ex.getLockedUntil()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorEnvelope("INTERNAL_ERROR", ex.getMessage()));
    }
    
    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ResponseEntity<?> handleDuplicateIdempotencyKey(DuplicateIdempotencyKeyException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(errorEnvelope("DUPLICATE_IDEMPOTENCY_KEY", ex.getMessage()));
    }

    @ExceptionHandler(ExpenseNotFoundException.class)
    public ResponseEntity<?> handleExpenseNotFound(ExpenseNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(errorEnvelope("EXPENSE_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(FieldImmutableException.class)
    public ResponseEntity<?> handleFieldImmutable(FieldImmutableException ex) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorEnvelope("FIELD_IMMUTABLE_FOR_BANK_IMPORT", ex.getMessage()));
    }

    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<?> handleInvalidQuery(InvalidQueryException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorEnvelope("INVALID_QUERY", ex.getMessage()));
    }

    private Map<String, Object> errorEnvelope(String code, String message) {
        // create immutable map
        return Map.of("error", Map.of(
                "code",      code,
                "message",   message,
                "timestamp", Instant.now().toString(),
                "traceId",   UUID.randomUUID().toString() // placeholder until step 7
        ));
    }
}