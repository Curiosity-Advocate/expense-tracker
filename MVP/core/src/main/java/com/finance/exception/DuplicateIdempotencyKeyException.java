package com.finance.exception;

public class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException(String key) {
        super("Idempotency key already used within 24 hours: " + key);
    }
}
