package com.finance.domain;

public enum ParticipationType {
    INCLUSIVE,  // this category's spending counts toward the target amount
    EXCLUSIVE   // this category's spending is subtracted from the total (used in TOTAL targets)
}
