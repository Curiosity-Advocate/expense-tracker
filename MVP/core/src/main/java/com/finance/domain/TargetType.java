package com.finance.domain;

public enum TargetType {
    CATEGORY,        // exactly one inclusive category
    MULTI_CATEGORY,  // two or more inclusive categories
    TOTAL            // total monthly spending, with optional exclusive carve-outs
}
