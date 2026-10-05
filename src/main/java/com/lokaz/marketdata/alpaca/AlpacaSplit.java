package com.lokaz.marketdata.alpaca;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A forward or reverse split from GET /v1/corporate-actions. A 4-for-1 split has old_rate 1, new_rate 4. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaSplit(
        String symbol,
        @JsonProperty("ex_date") LocalDate exDate,
        @JsonProperty("old_rate") BigDecimal oldRate,
        @JsonProperty("new_rate") BigDecimal newRate) {
}
