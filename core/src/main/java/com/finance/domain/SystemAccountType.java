package com.finance.domain;

public enum SystemAccountType {
    CASH("Cash"),
    CRYPTO("Crypto");

    private final String displayName;

    SystemAccountType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}