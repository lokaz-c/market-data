package com.lokaz.marketdata.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/** A raw daily bar ready to be written to the bars table; {@code feed} is the Alpaca feed it was requested from. */
public record BarRow(
        int symbolId,
        LocalDate ts,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        @Nullable Long tradeCount,
        @Nullable BigDecimal vwap,
        @Nullable String feed) {

    /**
     * Checks the same invariants as the CHECK constraints on bars. Rejecting a bad row here means one
     * malformed bar is logged and skipped instead of failing the whole batch.
     *
     * @return the first violated rule, or empty if the row is valid
     */
    public Optional<String> violation() {
        if (open == null || high == null || low == null || close == null) {
            return Optional.of("missing price");
        }
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0) {
            return Optional.of("non-positive price");
        }
        if (high.compareTo(low) < 0) {
            return Optional.of("high < low");
        }
        if (outside(open) || outside(close)) {
            return Optional.of("open or close outside [low, high]");
        }
        if (volume < 0) {
            return Optional.of("negative volume");
        }
        if (tradeCount != null && tradeCount < 0) {
            return Optional.of("negative trade count");
        }
        if (vwap != null && vwap.signum() <= 0) {
            return Optional.of("non-positive vwap");
        }
        return Optional.empty();
    }

    private boolean outside(BigDecimal price) {
        return price.compareTo(low) < 0 || price.compareTo(high) > 0;
    }
}
