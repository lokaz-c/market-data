package com.lokaz.marketdata.alpaca;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lokaz.marketdata.config.TimeConfig;

/**
 * One bar as returned by GET /v2/stocks/bars. Prices are parsed straight into BigDecimal so no
 * binary floating-point value is ever stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaBar(
        @JsonProperty("t") Instant timestamp,
        @JsonProperty("o") BigDecimal open,
        @JsonProperty("h") BigDecimal high,
        @JsonProperty("l") BigDecimal low,
        @JsonProperty("c") BigDecimal close,
        @JsonProperty("v") long volume,
        @JsonProperty("n") Long tradeCount,
        @JsonProperty("vw") BigDecimal vwap) {

    /** Daily bars are stamped at midnight New York time (e.g. 2023-09-29T04:00:00Z); this is that date. */
    public LocalDate sessionDate() {
        return timestamp.atZone(TimeConfig.MARKET_ZONE).toLocalDate();
    }
}
