package com.finance.exception;

public class InvalidCategoryWeightsException extends RuntimeException {
    public InvalidCategoryWeightsException(String detail) {
        super(detail);
    }
}
