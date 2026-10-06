package com.lokaz.marketdata.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Plain-Java reference for the SQL indicators, written from the definitions rather than from the SQL:
 * loops over a list, doubles for averages and standard deviations.
 */
final class IndicatorReference {

    private IndicatorReference() {
    }

    /** Mean of the last n closes ending at index i, or null if fewer than n. */
    static Double sma(List<TestSeries.Bar> bars, int i, int n) {
        if (i + 1 < n) {
            return null;
        }
        double sum = 0;
        for (int k = i - n + 1; k <= i; k++) {
            sum += bars.get(k).close().doubleValue();
        }
        return sum / n;
    }

    /** Sample stdev of the last 20 log returns ending at i, times sqrt(252). Needs 21 closes. */
    static Double volatility20(List<TestSeries.Bar> bars, int i) {
        if (i < 20) {
            return null;
        }
        double[] r = new double[20];
        for (int k = 0; k < 20; k++) {
            int day = i - 19 + k;
            r[k] = Math.log(bars.get(day).close().doubleValue() / bars.get(day - 1).close().doubleValue());
        }
        double mean = 0;
        for (double x : r) {
            mean += x;
        }
        mean /= r.length;
        double ss = 0;
        for (double x : r) {
            ss += (x - mean) * (x - mean);
        }
        return Math.sqrt(ss / (r.length - 1)) * Math.sqrt(252);
    }

    /** True range at day i (needs i >= 1). */
    static double trueRange(List<TestSeries.Bar> bars, int i) {
        double h = bars.get(i).high().doubleValue();
        double l = bars.get(i).low().doubleValue();
        double pc = bars.get(i - 1).close().doubleValue();
        return Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
    }

    /** Simple mean of the last 14 true ranges ending at i. Needs 15 bars. */
    static Double atr14(List<TestSeries.Bar> bars, int i) {
        if (i < 14) {
            return null;
        }
        double sum = 0;
        for (int k = i - 13; k <= i; k++) {
            sum += trueRange(bars, k);
        }
        return sum / 14;
    }

    /** Highest high over bars dated within 52 weeks (364 days) up to and including day i. */
    static BigDecimal high52w(List<TestSeries.Bar> bars, int i) {
        LocalDate cutoff = bars.get(i).date().minusDays(364);
        BigDecimal max = null;
        for (int k = 0; k <= i; k++) {
            if (!bars.get(k).date().isBefore(cutoff)) {
                max = max == null ? bars.get(k).high() : max.max(bars.get(k).high());
            }
        }
        return max;
    }

    static BigDecimal low52w(List<TestSeries.Bar> bars, int i) {
        LocalDate cutoff = bars.get(i).date().minusDays(364);
        BigDecimal min = null;
        for (int k = 0; k <= i; k++) {
            if (!bars.get(k).date().isBefore(cutoff)) {
                min = min == null ? bars.get(k).low() : min.min(bars.get(k).low());
            }
        }
        return min;
    }

    /** Highest high / lowest low of the last n bars ending at i. */
    static BigDecimal highN(List<TestSeries.Bar> bars, int i, int n) {
        BigDecimal max = bars.get(i).high();
        for (int k = i - n + 1; k <= i; k++) {
            max = max.max(bars.get(k).high());
        }
        return max;
    }

    static BigDecimal lowN(List<TestSeries.Bar> bars, int i, int n) {
        BigDecimal min = bars.get(i).low();
        for (int k = i - n + 1; k <= i; k++) {
            min = min.min(bars.get(k).low());
        }
        return min;
    }

    /** Classic floor pivot from bar j: P = (H + L + C) / 3. */
    static double pivot(TestSeries.Bar bar) {
        return (bar.high().doubleValue() + bar.low().doubleValue() + bar.close().doubleValue()) / 3;
    }
}
