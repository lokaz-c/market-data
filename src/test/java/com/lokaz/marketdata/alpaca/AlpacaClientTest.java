package com.lokaz.marketdata.alpaca;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

class AlpacaClientTest {

    private static final WireMockServer server = new WireMockServer(options().dynamicPort());
    private static final LocalDate START = LocalDate.of(2024, 1, 2);
    private static final LocalDate END = LocalDate.of(2024, 1, 5);
    private static final Instant NOW = Instant.parse("2026-10-05T20:30:00Z");

    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeAll
    static void start() {
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private AlpacaClient client(int symbolsPerRequest) {
        var retry = new AlpacaProperties.Retry(3, Duration.ofMillis(1), Duration.ofSeconds(30));
        var props = new AlpacaProperties(URI.create(server.baseUrl()), "key-id", "secret", "iex", 10_000,
                symbolsPerRequest, Duration.ofSeconds(2), Duration.ofSeconds(5), retry);
        return new AlpacaClient(props, new Retrier(retry, new Random(1), sleeps::add, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static String bar(String t, String close) {
        return """
                {"t":"%s","o":%s,"h":%s,"l":%s,"c":%s,"v":1000,"n":10,"vw":%s}""".formatted(t, close, close, close, close, close);
    }

    @Test
    void sendsKeysAndTheDocumentedQueryParameters() {
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars"))
                .willReturn(okJson("{\"bars\":{\"AAPL\":[" + bar("2024-01-02T05:00:00Z", "185.64") + "]},\"next_page_token\":null}")));

        var bars = client(100).fetchDailyBars(List.of("AAPL", "TSLA"), START, END, Adjustment.RAW);

        assertThat(bars.get("AAPL")).singleElement().satisfies(b -> {
            assertThat(b.close()).isEqualByComparingTo(new BigDecimal("185.64"));
            assertThat(b.volume()).isEqualTo(1000);
        });
        server.verify(getRequestedFor(urlPathEqualTo("/v2/stocks/bars"))
                .withHeader("APCA-API-KEY-ID", equalTo("key-id"))
                .withHeader("APCA-API-SECRET-KEY", equalTo("secret"))
                .withQueryParam("symbols", equalTo("AAPL,TSLA"))
                .withQueryParam("timeframe", equalTo("1Day"))
                .withQueryParam("start", equalTo("2024-01-02"))
                .withQueryParam("end", equalTo("2024-01-05"))
                .withQueryParam("adjustment", equalTo("raw"))
                .withQueryParam("feed", equalTo("iex"))
                .withQueryParam("limit", equalTo("10000"))
                .withQueryParam("sort", equalTo("asc"))
                .withQueryParam("page_token", absent()));
    }

    @Test
    void followsNextPageTokenUntilItIsNullAndEncodesIt() {
        String cursor = "QUFQTHxEfDIwMjQ+MDEvMDM="; // fake base64 page cursor with '+', '/' and '='
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("page_token", absent())
                .willReturn(okJson("{\"bars\":{\"AAPL\":[" + bar("2024-01-02T05:00:00Z", "185.64") + "]},\"next_page_token\":\"" + cursor + "\"}")));
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("page_token", equalTo(cursor))
                .willReturn(okJson("{\"bars\":{\"AAPL\":[" + bar("2024-01-03T05:00:00Z", "184.25") + "]},\"next_page_token\":null}")));

        var pages = new ArrayList<Integer>();
        client(100).forEachDailyBarsPage(List.of("AAPL"), START, END, Adjustment.RAW, Retrier.RetryListener.NONE,
                page -> pages.add(page.get("AAPL").size()));

        assertThat(pages).containsExactly(1, 1);
        server.verify(2, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
    }

    @Test
    void sendsLargeSymbolListsInChunks() {
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).willReturn(okJson("{\"bars\":{},\"next_page_token\":null}")));

        client(2).fetchDailyBars(List.of("AAPL", "MSFT", "NVDA"), START, END, Adjustment.RAW);

        server.verify(getRequestedFor(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("symbols", equalTo("AAPL,MSFT")));
        server.verify(getRequestedFor(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("symbols", equalTo("NVDA")));
    }

    @Test
    void datesDailyBarsByTheNewYorkSessionInSummerAndWinter() {
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).willReturn(okJson(
                "{\"bars\":{\"AAPL\":[" + bar("2023-09-29T04:00:00Z", "171.29") + "," + bar("2024-01-02T05:00:00Z", "185.64")
                        + "]},\"next_page_token\":null}")));

        var bars = client(100).fetchDailyBars(List.of("AAPL"), START, END, Adjustment.RAW).get("AAPL");

        assertThat(bars).extracting(AlpacaBar::sessionDate)
                .containsExactly(LocalDate.of(2023, 9, 29), LocalDate.of(2024, 1, 2));
    }

    @Test
    void retriesA429AndWaitsForTheRateLimitReset() {
        long reset = NOW.plusSeconds(7).getEpochSecond();
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("X-RateLimit-Reset", String.valueOf(reset))
                        .withBody("{\"message\":\"too many requests.\"}"))
                .willSetStateTo("recovered"));
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).inScenario("rate-limit")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"bars\":{\"AAPL\":[" + bar("2024-01-02T05:00:00Z", "185.64") + "]},\"next_page_token\":null}")));

        var retries = new ArrayList<Integer>();
        var pages = new ArrayList<Integer>();
        client(100).forEachDailyBarsPage(List.of("AAPL"), START, END, Adjustment.RAW,
                (n, wait, cause) -> retries.add(n), page -> pages.add(page.size()));

        assertThat(pages).containsExactly(1);
        assertThat(retries).containsExactly(1);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
        server.verify(2, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
    }

    @Test
    void givesUpOnPersistent5xxWithTheStatusInTheError() {
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).willReturn(aResponse().withStatus(503).withBody("unavailable")));

        assertThatThrownBy(() -> client(100).fetchDailyBars(List.of("AAPL"), START, END, Adjustment.RAW))
                .isInstanceOfSatisfying(AlpacaHttpException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("unavailable");
                });
        server.verify(3, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
    }

    @Test
    void doesNotRetryAForbiddenResponse() {
        server.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).willReturn(aResponse().withStatus(403)
                .withBody("{\"code\":42210000,\"message\":\"subscription does not permit querying recent SIP data\"}")));

        assertThatThrownBy(() -> client(100).fetchDailyBars(List.of("AAPL"), START, END, Adjustment.RAW))
                .isInstanceOf(AlpacaHttpException.class);
        server.verify(1, getRequestedFor(urlPathEqualTo("/v2/stocks/bars")));
    }

    @Test
    void readsForwardAndReverseSplitsAcrossPages() {
        server.stubFor(get(urlPathEqualTo("/v1/corporate-actions")).withQueryParam("page_token", absent())
                .willReturn(okJson("""
                        {"corporate_actions":{"forward_splits":[{"cusip":"816851109","due_bill_redemption_date":"2023-08-23",
                         "ex_date":"2023-08-22","id":"189bd849-ab9f-4b4d-aaaa-a6d415fd976d","new_rate":2,"old_rate":1,
                         "payable_date":"2023-08-21","process_date":"2023-08-22","record_date":"2023-08-14","symbol":"SRE"}]},
                         "next_page_token":"abc"}""")));
        server.stubFor(get(urlPathEqualTo("/v1/corporate-actions")).withQueryParam("page_token", equalTo("abc"))
                .willReturn(okJson("""
                        {"corporate_actions":{"reverse_splits":[{"symbol":"MNTS","ex_date":"2023-06-21","old_rate":50,
                         "new_rate":1,"process_date":"2023-06-21","id":"x","cusip":"y"}]},"next_page_token":null}""")));

        var splits = client(100).fetchSplits(List.of("SRE", "MNTS"), START, END, Retrier.RetryListener.NONE);

        assertThat(splits).extracting(AlpacaSplit::symbol, AlpacaSplit::exDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SRE", LocalDate.of(2023, 8, 22)),
                        org.assertj.core.groups.Tuple.tuple("MNTS", LocalDate.of(2023, 6, 21)));
        assertThat(splits.get(1).oldRate()).isEqualByComparingTo("50");
        server.verify(getRequestedFor(urlPathEqualTo("/v1/corporate-actions"))
                .withQueryParam("types", equalTo("forward_split,reverse_split")));
    }
}
