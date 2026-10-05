package com.lokaz.marketdata.ingestion;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param symbols tickers the daily job keeps up to date (INGEST_SYMBOLS, comma-separated)
 */
@Validated
@ConfigurationProperties("market-data.ingestion")
public record IngestionProperties(@NotNull List<String> symbols, @Valid @NotNull Schedule schedule) {

    /**
     * @param lookbackDays the daily job re-fetches this many days so a missed run or a late correction from
     *                     Alpaca is picked up on the next run; upserts make the overlap free
     */
    public record Schedule(boolean enabled, @NotNull String cron, @NotNull String zone, @Min(1) int lookbackDays) {
    }
}
