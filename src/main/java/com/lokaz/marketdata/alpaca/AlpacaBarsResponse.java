package com.lokaz.marketdata.alpaca;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** Multi-symbol bars page: {"bars": {"AAPL": [...]}, "next_page_token": "..." | null}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record AlpacaBarsResponse(
        @Nullable Map<String, List<AlpacaBar>> bars,
        @JsonProperty("next_page_token") @Nullable String nextPageToken) {

    Map<String, List<AlpacaBar>> barsOrEmpty() {
        return bars == null ? Map.of() : bars;
    }
}
