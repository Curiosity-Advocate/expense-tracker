package com.finance.domain;

// Confidence level assigned by a PredictionStrategy based on how much
// of the period has elapsed. The NaiveDailyRateStrategy derives this from
// the percentage of days remaining:
//   LOW    — > 80% of period remaining (early in the month, too little data)
//   MEDIUM — 40-80% remaining
//   HIGH   — < 40% remaining (most of the month is done, rate is reliable)
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
}
