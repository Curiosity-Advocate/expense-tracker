package com.finance.exception;

// Thrown when a query object is constructed with invalid parameters.
// Examples: dateFrom after dateTo, missing required fields.
// Results in 400 Bad Request — this is a client error, not a server error.
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}