package com.finance.exception;

import java.util.UUID;

public class TargetNotFoundException extends RuntimeException {
    public TargetNotFoundException(UUID id) {
        super("Target not found: " + id);
    }
}
