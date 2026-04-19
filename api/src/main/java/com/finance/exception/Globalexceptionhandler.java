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