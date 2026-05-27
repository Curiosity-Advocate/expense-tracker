package com.finance.prediction;

import com.finance.domain.Confidence;
import com.finance.domain.PredictionResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// v1.0 prediction strategy: projects end-of-month spend using the observed daily rate.
// projectedAmount = (spentSoFar / daysElapsed) * totalDaysInMonth
//
// Open/Closed Principle: to add a new algorithm, create a new strategy class.
// Never modify this class — historical predictions must stay reproducible.
public class NaiveDailyRateStrategy {

    public static final String NAME = "NaiveDailyRate";
    public static final String VERSION = "1.0";

    private final Clock clock;

    public NaiveDailyRateStrategy() {
        this(Clock.systemUTC());
    }

    NaiveDailyRateStrategy(Clock clock) {
        this.clock = clock;
    }

    // Returns null when daysElapsed == 0 (no data yet — first day of the period).
    public PredictionResult predict(BigDecimal targetAmount, BigDecimal spentAmount,
                                    int periodYear, int periodMonth) {
        LocalDate periodStart = LocalDate.of(periodYear, periodMonth, 1);
        LocalDate periodEnd = periodStart.plusMonths(1);
        LocalDate today = LocalDate.now(clock);

        int totalDays = (int) ChronoUnit.DAYS.between(periodStart, periodEnd);
        LocalDate effectiveToday = today.isBefore(periodEnd) ? today : periodEnd;
        int daysElapsed = (int) ChronoUnit.DAYS.between(periodStart, effectiveToday);
        int daysRemaining = totalDays - daysElapsed;

        if (daysElapsed == 0) return null;

        BigDecimal dailyRate = spentAmount.divide(BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_EVEN);
        BigDecimal projected = dailyRate.multiply(BigDecimal.valueOf(totalDays))
                                        .setScale(2, RoundingMode.HALF_EVEN);

        // LOW  = >80% of month remaining (early, low signal)
        // MEDIUM = 40-80% remaining
        // HIGH = <40% remaining (late month, strong signal)
        double remainingRatio = (double) daysRemaining / totalDays;
        Confidence confidence = remainingRatio > 0.8 ? Confidence.LOW
                              : remainingRatio > 0.4 ? Confidence.MEDIUM
                              : Confidence.HIGH;

        boolean willExceed = projected.compareTo(targetAmount) > 0;
        BigDecimal exceedance = willExceed ? projected.subtract(targetAmount).setScale(2, RoundingMode.HALF_EVEN) : null;

        return new PredictionResult(projected, willExceed, exceedance, NAME, VERSION,
                confidence, daysElapsed, daysRemaining);
    }
}
