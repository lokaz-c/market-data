package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class BarsApiIT extends ApiTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);

    private List<TestSeries.Bar> load(String ticker, String source, int count) {
        List<TestSeries.Bar> bars = TestSeries.weekdays(START, count);
        insertBars(insertSymbol(ticker, source), bars);
        return bars;
    }

    @Test
    void keysetPagesCoverTheRangeExactlyOnceInOrder() {
        List<TestSeries.Bar> bars = load("SYNA", "synthetic", 250);
        LocalDate from = bars.get(10).date();
        LocalDate to = bars.get(239).date();

        var dates = new ArrayList<String>();
        String after = null;
        int pages = 0;
        do {
            Response page = get("/v1/bars/SYNA?from=" + from + "&to=" + to + "&limit=100"
                    + (after == null ? "" : "&after=" + after));
            assertThat(page.status()).isEqualTo(200);
            page.json().get("bars").forEach(b -> dates.add(b.get("date").asString()));
            JsonNode next = page.json().get("nextAfter");
            after = next.isNull() ? null : next.asString();
            pages++;
        } while (after != null);

        assertThat(pages).isEqualTo(3); // 230 rows at 100 per page
        assertThat(dates).hasSize(230).doesNotHaveDuplicates().isSorted();
        assertThat(dates.getFirst()).isEqualTo(from.toString());
        assertThat(dates.getLast()).isEqualTo(to.toString());
    }

    @Test
    void defaultsToTheYearBeforeTheLatestBarAndTrimsPrices() {
        List<TestSeries.Bar> bars = load("SYNA", "synthetic", 400);
        JsonNode page = get("/v1/bars/SYNA?limit=1000").json();

        LocalDate last = bars.getLast().date();
        assertThat(page.get("to").asString()).isEqualTo(last.toString());
        assertThat(page.get("from").asString()).isEqualTo(last.minusDays(365).toString());
        assertThat(page.get("adjustment").asString()).isEqualTo("split");
        assertThat(page.get("source").asString()).isEqualTo("synthetic");
        JsonNode first = page.get("bars").get(0);
        assertThat(first.get("close").toString()).doesNotContain("E").doesNotEndWith("0000");
    }

    @Test
    void rawAndSplitAdjustedPricesDifferBeforeTheExDate() {
        int id = insertSymbol("SPLT", "synthetic");
        insertBars(id, List.of(new TestSeries.Bar(LocalDate.of(2024, 1, 2), TestSeries.money(400), TestSeries.money(410),
                TestSeries.money(390), TestSeries.money(400), 1000)));
        insertSplit(id, LocalDate.of(2024, 1, 3), 1, 4);

        JsonNode split = get("/v1/bars/SPLT?from=2024-01-01&to=2024-01-05").json().get("bars").get(0);
        JsonNode raw = get("/v1/bars/SPLT?from=2024-01-01&to=2024-01-05&adjustment=raw").json().get("bars").get(0);

        assertThat(decimal(split.get("close"))).isEqualByComparingTo("100");
        assertThat(split.get("volume").asLong()).isEqualTo(4000);
        assertThat(decimal(raw.get("close"))).isEqualByComparingTo("400");
        assertThat(raw.get("volume").asLong()).isEqualTo(1000);
    }

    @Test
    void unknownTickerIsAProblemDetail404() {
        Response response = get("/v1/bars/NOPE");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        JsonNode problem = response.json();
        // RFC 9457 section 3.1.1: an absent "type" means "about:blank" (Spring omits it).
        assertThat(problem.has("type")).isFalse();
        assertThat(problem.get("title").asString()).isEqualTo("Not Found");
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.get("detail").asString()).contains("NOPE");
        assertThat(problem.get("instance").asString()).isEqualTo("/v1/bars/NOPE");
    }

    @Test
    void invalidParametersAreProblemDetail400sNamingTheParameter() {
        load("SYNA", "synthetic", 5);

        Response badTicker = get("/v1/bars/AA$");
        assertThat(badTicker.status()).isEqualTo(400);
        assertThat(badTicker.json().get("errors").get(0).get("parameter").asString()).isEqualTo("ticker");

        Response tooMany = get("/v1/bars/SYNA?limit=5000");
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.json().get("errors").get(0).get("parameter").asString()).isEqualTo("limit");

        Response badAdjustment = get("/v1/bars/SYNA?adjustment=dividend");
        assertThat(badAdjustment.status()).isEqualTo(400);

        Response reversed = get("/v1/bars/SYNA?from=2024-02-01&to=2024-01-01");
        assertThat(reversed.status()).isEqualTo(400);
        assertThat(reversed.json().get("detail").asString()).contains("after");

        Response badDate = get("/v1/bars/SYNA?from=yesterday");
        assertThat(badDate.status()).isEqualTo(400);
        assertThat(badDate.header("Content-Type")).startsWith("application/problem+json");
    }

    @Test
    void alpacaDataIsHiddenWithoutAKeyAndVisibleWithOne() {
        load("AAPL", "alpaca", 5);

        assertThat(get("/v1/bars/AAPL").status()).isEqualTo(404);
        assertThat(get("/v1/symbols").json().get("symbols").size()).isZero();

        Response withKey = getWithKey("/v1/bars/AAPL", API_KEY);
        assertThat(withKey.status()).isEqualTo(200);
        assertThat(withKey.json().get("source").asString()).isEqualTo("alpaca");
        assertThat(withKey.header("Cache-Control")).contains("private");

        Response wrongKey = getWithKey("/v1/bars/AAPL", "not-the-key");
        assertThat(wrongKey.status()).isEqualTo(401);
        assertThat(wrongKey.header("Content-Type")).startsWith("application/problem+json");
    }

    @Test
    void publicResponsesAreCacheableForFiveMinutes() {
        load("SYNA", "synthetic", 5);
        Response response = get("/v1/bars/SYNA");
        assertThat(response.header("Cache-Control")).contains("max-age=300").contains("public");
    }

    @Test
    void symbolsListsOnlySymbolsWithDataAndFiltersByPrefix() {
        load("SYNA", "synthetic", 5);
        load("SYNB", "synthetic", 5);
        load("OTHR", "synthetic", 5);
        insertSymbol("EMPTY", "synthetic");

        JsonNode all = get("/v1/symbols").json().get("symbols");
        assertThat(all.size()).isEqualTo(3);
        JsonNode filtered = get("/v1/symbols?q=syn").json().get("symbols");
        assertThat(filtered.size()).isEqualTo(2);
        assertThat(filtered.get(0).get("ticker").asString()).isEqualTo("SYNA");
        assertThat(filtered.get(0).get("firstBar").asString()).isEqualTo("2024-01-02");
        assertThat(filtered.get(0).get("lastClose")).isNotNull();
    }
}
