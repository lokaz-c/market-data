package com.lokaz.marketdata.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.jspecify.annotations.Nullable;

/** Response bodies. Prices are split-adjusted unless {@code adjustment} says "raw". */
public final class Dtos {

    private Dtos() {
    }

    public record SymbolSummary(String ticker, @Nullable String name, String source, LocalDate firstBar,
            LocalDate lastBar, BigDecimal lastClose) {
    }

    public record SymbolList(List<SymbolSummary> symbols) {
    }

    public record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            long volume) {
    }

    /** @param nextAfter pass as {@code after} to get the next page; null on the last page */
    public record BarsPage(String ticker, String source, String adjustment, LocalDate from, LocalDate to,
            List<Bar> bars, @Nullable LocalDate nextAfter) {
    }

    /**
     * @param volatility20 annualised: sample stdev of 20 daily log returns times sqrt(252)
     * @param atr14        simple 14-day mean of true range
     * @param pivot        classic floor pivot for this session, from the previous session; r1/s1 likewise
     */
    public record IndicatorRow(LocalDate date, BigDecimal close, @Nullable BigDecimal sma20,
            @Nullable BigDecimal sma50, @Nullable BigDecimal sma200, @Nullable BigDecimal volatility20,
            @Nullable BigDecimal atr14, @Nullable BigDecimal high52w, @Nullable BigDecimal low52w,
            @Nullable BigDecimal pivot, @Nullable BigDecimal r1, @Nullable BigDecimal s1) {
    }

    public record IndicatorsPage(String ticker, String source, LocalDate from, LocalDate to,
            List<IndicatorRow> indicators, @Nullable LocalDate nextAfter) {
    }

    /** Classic floor pivots computed from the {@code basedOn} session, for the session after it. */
    public record Pivots(LocalDate basedOn, BigDecimal p, BigDecimal r1, BigDecimal r2, BigDecimal r3,
            BigDecimal s1, BigDecimal s2, BigDecimal s3) {
    }

    /** High and low over a window; both null when the symbol does not have that much history yet. */
    public record Range(@Nullable BigDecimal high, @Nullable BigDecimal low) {
    }

    public record Levels(String ticker, String source, LocalDate asOf, BigDecimal close, Pivots pivots,
            Range range20d, Range range50d, Range range52w) {
    }
}
