package com.finance.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// Converts every application exception into the standard error envelope:
// { "error": { "code": "...", "message": "...", "timestamp": "...", "traceId": "..." } }
//
// traceId is a placeholder UUID here. A proper traceId injected at the gateway
// filter level is a future enhancement once request correlation is added.
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Auth ──────────────────────────────────────────────────────────────────

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<?> handleUserAlreadyExists() {
        return error(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", "Username or email already in use");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<?> handleInvalidCredentials() {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<?> handleAccountLocked(AccountLockedException ex) {
        // 423 Locked is more precise than 401 — tells the client to retry later.
        return error(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                "Account locked until " + ex.getLockedUntil());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<?> handleUserNotFound(UserNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage());
    }

    // ── Validation (Jakarta Bean Validation failures) ─────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    // ── Expenses ──────────────────────────────────────────────────────────────
    // Handlers added progressively as exception classes are created in each step.
    // Defined here so the handler class is the single place to look.

    @ExceptionHandler(ExpenseNotFoundException.class)
    public ResponseEntity<?> handleExpenseNotFound(ExpenseNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "EXPENSE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(FieldImmutableException.class)
    public ResponseEntity<?> handleFieldImmutable(FieldImmutableException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "FIELD_IMMUTABLE_FOR_BANK_IMPORT", ex.getMessage());
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ResponseEntity<?> handleDuplicateIdempotencyKey(DuplicateIdempotencyKeyException ex) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_IDEMPOTENCY_KEY", ex.getMessage());
    }

    @ExceptionHandler(InvalidCategoryWeightsException.class)
    public ResponseEntity<?> handleInvalidCategoryWeights(InvalidCategoryWeightsException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY_WEIGHTS", ex.getMessage());
    }

    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<?> handleInvalidQuery(InvalidQueryException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY", ex.getMessage());
    }

    // ── Categories ────────────────────────────────────────────────────────────

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<?> handleCategoryNotFound(CategoryNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(SystemCategoryImmutableException.class)
    public ResponseEntity<?> handleSystemCategoryImmutable(SystemCategoryImmutableException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_CATEGORY_IMMUTABLE", ex.getMessage());
    }

    @ExceptionHandler(CategoryAlreadyExistsException.class)
    public ResponseEntity<?> handleCategoryAlreadyExists(CategoryAlreadyExistsException ex) {
        return error(HttpStatus.CONFLICT, "CATEGORY_ALREADY_EXISTS", ex.getMessage());
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    @ExceptionHandler(TargetNotFoundException.class)
    public ResponseEntity<?> handleTargetNotFound(TargetNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(TargetAlreadyExistsException.class)
    public ResponseEntity<?> handleTargetAlreadyExists(TargetAlreadyExistsException ex) {
        return error(HttpStatus.CONFLICT, "TARGET_ALREADY_EXISTS", ex.getMessage());
    }

    @ExceptionHandler(InvalidTargetScopeException.class)
    public ResponseEntity<?> handleInvalidTargetScope(InvalidTargetScopeException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TARGET_SCOPE", ex.getMessage());
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "code",      code,
                "message",   message,
                "timestamp", Instant.now().toString(),
                "traceId",   UUID.randomUUID().toString()
        )));
    }
}
