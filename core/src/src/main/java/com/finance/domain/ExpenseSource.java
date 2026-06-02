package com.finance.domain;

public enum ExpenseSource {
    MANUAL,
    BANK_IMPORT  // reserved for v2.0 bank sync — defined now so the column exists from day one
}
