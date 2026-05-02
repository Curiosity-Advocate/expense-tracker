package com.finance.exception;

import java.util.UUID;

// Thrown when an idempotency key is found in the table but the original
// expense cannot be retrieved — indicates data inconsistency.
// In normal retry scenarios the original expense is returned silently.
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(UUID idempotencyKey) {
        super("Idempotency key already used but original expense not found: " + idempotencyKey);
    }
}