package com.finance.exception;

public class SystemCategoryImmutableException extends RuntimeException {
    public SystemCategoryImmutableException() {
        super("System categories cannot be modified or deleted");
    }
}
