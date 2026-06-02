package com.finance.exception;

public class FieldImmutableException extends RuntimeException {
    public FieldImmutableException(String fieldName) {
        super("Field '" + fieldName + "' cannot be modified on a bank-imported expense");
    }
}
