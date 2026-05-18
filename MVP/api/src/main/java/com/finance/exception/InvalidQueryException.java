package com.finance.exception;

public class InvalidQueryException extends RuntimeException {
    public InvalidQueryException(String detail) {
        super(detail);
    }
}
