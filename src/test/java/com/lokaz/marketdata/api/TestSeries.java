package com.lokaz.marketdata.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Deterministic daily bars for indicator tests: a trend plus a sine wave, weekdays only. */
final class TestSeries {

    record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
    }

    private TestSeries() {
    }

    static List<Bar> weekdays(LocalDate start, int count) {
        var bars = new ArrayList<Bar>();
        LocalDate date = start;
        BigDecimal prevClose = money(100);
        for (int i = 0; i < count; i++) {
            while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
            }
            BigDecimal close = money(100 + 10 * Math.sin(i / 9.0) + 0.05 * i + (i % 7) * 0.3);
            BigDecimal open = money(prevClose.doubleValue() + ((i % 4) - 1.5) * 0.4);
            BigDecimal high = open.max(close).add(money(0.5 + (i % 3) * 0.35));
            BigDecimal low = open.min(close).subtract(money(0.4 + (i % 5) * 0.25));
            bars.add(new Bar(date, open, high, low, close, 1_000_000L + i * 1_000L));
            prevClose = close;
            date = date.plusDays(1);
        }
        return bars;
    }

    static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
