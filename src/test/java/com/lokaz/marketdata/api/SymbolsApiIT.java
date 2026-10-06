package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class SymbolsApiIT extends ApiTest {

    private void load(String... tickers) {
        for (String ticker : tickers) {
            insertBars(insertSymbol(ticker, "synthetic"), TestSeries.weekdays(LocalDate.of(2024, 1, 2), 3));
        }
    }

    private List<List<String>> pages(String query) {
        var pages = new ArrayList<List<String>>();
        String after = null;
        do {
            JsonNode page = get("/v1/symbols?" + query + (after == null ? "" : "&after=" + after)).json();
            var tickers = new ArrayList<String>();
            page.get("symbols").forEach(s -> tickers.add(s.get("ticker").asString()));
            pages.add(tickers);
            after = page.get("nextAfter").isNull() ? null : page.get("nextAfter").asString();
        } while (after != null && pages.size() < 10);
        return pages;
    }

    @Test
    void keysetPagesCoverEverySymbolOnceInTickerOrder() {
        load("EEE", "AAA", "DDD", "BBB", "CCC");

        assertThat(pages("limit=2")).containsExactly(List.of("AAA", "BBB"), List.of("CCC", "DDD"), List.of("EEE"));
        assertThat(pages("limit=5")).containsExactly(List.of("AAA", "BBB", "CCC", "DDD", "EEE"));
    }

    @Test
    void thePrefixFilterAndTheCursorCombine() {
        load("SYNA", "SYNB", "SYNC", "OTHR");

        assertThat(pages("q=syn&limit=2")).containsExactly(List.of("SYNA", "SYNB"), List.of("SYNC"));
    }

    @Test
    void syntheticSymbolsHaveNoFeedOrIngestionTime() {
        load("SYNA");

        JsonNode symbol = get("/v1/symbols").json().get("symbols").get(0);

        assertThat(symbol.get("feed").isNull()).isTrue();
        assertThat(symbol.get("lastIngestedAt").isNull()).isTrue();
        assertThat(symbol.get("lastBar").asString()).isEqualTo("2024-01-04");
    }

    @Test
    void theCursorIsValidatedAndPagesAreCapped() {
        assertThat(get("/v1/symbols?after=%24bad").status()).isEqualTo(400);
        Response tooMany = get("/v1/symbols?limit=201");
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.json().get("errors").get(0).get("parameter").asString()).isEqualTo("limit");
    }
}
