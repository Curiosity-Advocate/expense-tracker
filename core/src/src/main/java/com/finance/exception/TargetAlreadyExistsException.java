package com.finance.exception;

public class TargetAlreadyExistsException extends RuntimeException {
    public TargetAlreadyExistsException(String detail) {
        super(detail);
    }
}
