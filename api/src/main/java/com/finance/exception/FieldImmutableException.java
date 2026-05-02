package com.finance.exception;

// Thrown when a client attempts to update an immutable field.
// The reason is provided by the caller — the exception makes no assumptions
// about why the field is immutable.
public class FieldImmutableException extends RuntimeException {

    private final String fieldName;
    private final String reason;

    public FieldImmutableException(String fieldName, String reason) {
        super("Field '" + fieldName + "' cannot be modified: " + reason);
        this.fieldName = fieldName;
        this.reason = reason;
    }

    public String getFieldName() { return fieldName; }
    public String getReason()    { return reason; }
}