package com.finance.prediction;

import com.finance.domain.Confidence;
import com.finance.domain.PredictionResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class NaiveDailyRateStrategyTest {

    /*
     * Default clock: 2026-01-11 (day 11 of January)
     *   totalDays     = 31
     *   daysElapsed   = 10  (Jan 1 → Jan 11, exclusive end)
     *   daysRemaining = 21
     *   remainingRatio = 21/31 = 0.677 → MEDIUM confidence
     */
    private static Clock clockAt(String date) {
        return Clock.fixed(Instant.parse(date + "T00:00:00Z"), ZoneOffset.UTC);
    }

    private final NaiveDailyRateStrategy strategy = new NaiveDailyRateStrategy(clockAt("2026-01-11"));

    @Nested
    class WhenFirstDayOfPeriod {

        @Test
        void returnsNull_becauseNoDataYet() {
            var firstDayStrategy = new NaiveDailyRateStrategy(clockAt("2026-01-01"));
            BigDecimal targetAmount = new BigDecimal("500.00");
            BigDecimal spentAmount = BigDecimal.ZERO;

            PredictionResult result = firstDayStrategy.predict(targetAmount, spentAmount, 2026, 1);

            assertThat(result).isNull();
        }
    }

    @Nested
    class Projection {

        @Test
        void calculatesProjectedAmountCorrectly() {
            // daysElapsed=10, spent=$100 → dailyRate=$10 → projected=$310 (10 * 31)
            BigDecimal targetAmount = new BigDecimal("500.00");
            BigDecimal spentAmount = new BigDecimal("100.00");

            PredictionResult result = strategy.predict(targetAmount, spentAmount, 2026, 1);

            assertThat(result.projectedAmount()).isEqualByComparingTo("310.00");
        }

        @Test
        void setsWillExceedAndExceedance_whenProjectionAboveTarget() {
            // projected=$310, target=$200 → exceeds by $110
            BigDecimal targetAmount = new BigDecimal("200.00");
            BigDecimal spentAmount = new BigDecimal("100.00");

            PredictionResult result = strategy.predict(targetAmount, spentAmount, 2026, 1);

            assertThat(result.willExceedTarget()).isTrue();
            assertThat(result.projectedExceedanceAmount()).isEqualByComparingTo("110.00");
        }

        @Test
        void exceedanceIsNull_whenWithinTarget() {
            // projected=$310, target=$500 → no exceedance
            BigDecimal targetAmount = new BigDecimal("500.00");
            BigDecimal spentAmount = new BigDecimal("100.00");

            PredictionResult result = strategy.predict(targetAmount, spentAmount, 2026, 1);

            assertThat(result.willExceedTarget()).isFalse();
            assertThat(result.projectedExceedanceAmount()).isNull();
        }
    }

    @Nested
    class ConfidenceLevel {

        @Test
        void isLow_earlyInMonth() {
            // Day 3 → daysElapsed=2, daysRemaining=29, ratio=29/31=0.935 → LOW
            var earlyStrategy = new NaiveDailyRateStrategy(clockAt("2026-01-03"));
            BigDecimal targetAmount = new BigDecimal("500.00");
            BigDecimal spentAmount = new BigDecimal("10.00");

            PredictionResult result = earlyStrategy.predict(targetAmount, spentAmount, 2026, 1);

            assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        }

        @Test
        void isMedium_midMonth() {
            // Default clock: ratio=21/31=0.677 → MEDIUM
            BigDecimal targetAmount = new BigDecimal("500.00");
            BigDecimal spentAmount = new BigDecimal("100.00");

            PredictionResult result = strategy.predict(targetAmount, spentAmount, 2026, 1);

            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void isHigh_lateInMonth() {
            // Day 22 → daysElapsed=21, daysRemaining=10, ratio=10/31=0.322 → HIGH
            var lateStrategy = new NaiveDailyRateStrategy(clockAt("2026-01-22"));
            BigDecimal targetAmount = new BigDecimal("500.00");
            BigDecimal spentAmount = new BigDecimal("100.00");

            PredictionResult result = lateStrategy.predict(targetAmount, spentAmount, 2026, 1);

            assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        }
    }
}
