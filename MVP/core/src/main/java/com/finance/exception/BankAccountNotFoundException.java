package com.finance.exception;

import java.util.UUID;

public class BankAccountNotFoundException extends RuntimeException {
    public BankAccountNotFoundException(UUID id) {
        super("Bank account not found: " + id);
    }
}
