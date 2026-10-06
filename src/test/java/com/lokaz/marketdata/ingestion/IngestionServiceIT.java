package com.lokaz.marketdata.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.lokaz.marketdata.IntegrationTest;

class IngestionServiceIT extends IntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 2);
    private static final LocalDate TO = LocalDate.of(2024, 1, 4);
    private static final String NO_SPLITS = "{\"corporate_actions\":{},\"next_page_token\":null}";

    @Autowired
    IngestionService ingestion;

    @BeforeEach
    void noSplitsByDefault() {
        alpaca.stubFor(get(urlPathEqualTo("/v1/corporate-actions")).willReturn(okJson(NO_SPLITS)));
    }

    private static String bar(String date, String o, String h, String l, String c, long v) {
        return """
                {"t":"%sT05:00:00Z","o":%s,"h":%s,"l":%s,"c":%s,"v":%d,"n":100,"vw":%s}""".formatted(date, o, h, l, c, v, c);
    }

    private static String page(String aaplBars, String msftBars, String nextToken) {
        return "{\"bars\":{\"AAPL\":[" + aaplBars + "],\"MSFT\":[" + msftBars + "]},\"next_page_token\":"
                + (nextToken == null ? "null" : "\"" + nextToken + "\"") + "}";
    }

    private static final String AAPL_3_DAYS = String.join(",",
            bar("2024-01-02", "187.15", "188.44", "183.89", "185.64", 82488700),
            bar("2024-01-03", "184.22", "185.88", "183.43", "184.25", 58414500),
            bar("2024-01-04", "182.15", "183.09", "180.88", "181.91", 71983600));
    private static final String MSFT_3_DAYS = String.join(",",
            bar("2024-01-02", "373.86", "375.90", "366.77", "370.87", 25258600),
            bar("2024-01-03", "369.01", "373.26", "368.51", "370.60", 23083500),
            bar("2024-01-04", "370.67", "373.10", "367.17", "367.94", 20901500));

    private IngestionResult ingest() {
        return ingestion.ingest(new IngestionRequest(IngestionKind.BACKFILL, List.of("AAPL", "MSFT"), FROM, TO));
    }

    private void stubBars(String body) {
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).willReturn(okJson(body)));
    }

    @Test
    void runningTheSameIngestionTwiceGivesTheSameRowsAndUpsertsNothingTheSecondTime() {
        stubBars(page(AAPL_3_DAYS, MSFT_3_DAYS, null));

        IngestionResult first = ingest();
        IngestionResult second = ingest();

        assertThat(first.succeeded()).isTrue();
        assertThat(first.barsReceived()).isEqualTo(6);
        assertThat(first.barsUpserted()).isEqualTo(6);
        assertThat(second.succeeded()).isTrue();
        assertThat(second.barsReceived()).isEqualTo(6);
        assertThat(second.barsUpserted()).isZero();
        assertThat(count("bars")).isEqualTo(6);
        assertThat(count("symbols")).isEqualTo(2);

        List<Map<String, Object>> runs = jdbc.sql(
                "SELECT status, kind, bars_upserted, finished_at IS NOT NULL AS finished FROM ingestion_runs ORDER BY id")
                .query().listOfRows();
        assertThat(runs).extracting(r -> r.get("status")).containsExactly("succeeded", "succeeded");
        assertThat(runs).extracting(r -> r.get("kind")).containsOnly("backfill");
        assertThat(runs).extracting(r -> r.get("bars_upserted")).containsExactly(6, 0);
        assertThat(runs).extracting(r -> r.get("finished")).containsOnly(true);
    }

    @Test
    void aCorrectedBarFromAlpacaIsUpdatedInPlace() {
        stubBars(page(AAPL_3_DAYS, MSFT_3_DAYS, null));
        ingest();

        String corrected = AAPL_3_DAYS.replace("\"c\":184.25", "\"c\":184.30");
        alpaca.resetMappings();
        noSplitsByDefault();
        stubBars(page(corrected, MSFT_3_DAYS, null));
        IngestionResult result = ingest();

        assertThat(result.barsUpserted()).isEqualTo(1);
        assertThat(count("bars")).isEqualTo(6);
        BigDecimal close = jdbc.sql("""
                SELECT b.close FROM bars b JOIN symbols s ON s.id = b.symbol_id
                WHERE s.ticker = 'AAPL' AND b.ts = DATE '2024-01-03'""").query(BigDecimal.class).single();
        assertThat(close).isEqualByComparingTo("184.30");
    }

    @Test
    void recordsTheFeedOnEachBarAndTheRunOnEachSymbol() {
        stubBars(page(AAPL_3_DAYS, MSFT_3_DAYS, null));

        IngestionResult result = ingest();

        assertThat(jdbc.sql("SELECT DISTINCT feed FROM bars").query(String.class).list()).containsExactly("iex");
        List<Map<String, Object>> symbols = jdbc.sql("""
                SELECT s.ticker, s.last_ingestion_run_id, s.last_ingested_at = r.finished_at AS at_run_finish
                FROM symbols s JOIN ingestion_runs r ON r.id = s.last_ingestion_run_id
                ORDER BY s.ticker""").query().listOfRows();
        assertThat(symbols).extracting(r -> r.get("ticker")).containsExactly("AAPL", "MSFT");
        assertThat(symbols).extracting(r -> r.get("last_ingestion_run_id")).containsOnly(result.runId());
        assertThat(symbols).extracting(r -> r.get("at_run_finish")).containsOnly(true);
    }

    @Test
    void aFailedRunDoesNotCountAsTheLastIngestion() {
        stubBars(page(AAPL_3_DAYS, MSFT_3_DAYS, null));
        long succeeded = ingest().runId();
        alpaca.resetMappings();
        noSplitsByDefault();
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).willReturn(aResponse().withStatus(503)));

        assertThat(ingest().succeeded()).isFalse();

        assertThat(jdbc.sql("SELECT DISTINCT last_ingestion_run_id FROM symbols").query(Long.class).list())
                .containsExactly(succeeded);
    }

    @Test
    void aSymbolsDataVersionMovesOnlyWhenItsBarsChange() {
        stubBars(page(AAPL_3_DAYS, MSFT_3_DAYS, null));
        ingest();
        Map<String, String> first = dataVersions();

        ingest();
        assertThat(dataVersions()).as("an identical re-run").isEqualTo(first);

        alpaca.resetMappings();
        noSplitsByDefault();
        stubBars(page(AAPL_3_DAYS.replace("\"c\":184.25", "\"c\":184.30"), MSFT_3_DAYS, null));
        ingest();
        Map<String, String> corrected = dataVersions();
        assertThat(corrected.get("AAPL")).isNotEqualTo(first.get("AAPL"));
        assertThat(corrected.get("MSFT")).isEqualTo(first.get("MSFT"));
    }

    private Map<String, String> dataVersions() {
        var versions = new java.util.HashMap<String, String>();
        jdbc.sql("SELECT ticker, data_changed_at::text AS v FROM symbols").query()
                .listOfRows().forEach(r -> versions.put((String) r.get("ticker"), (String) r.get("v")));
        return versions;
    }

    @Test
    void invalidBarsAreSkippedAndCountedWithoutFailingTheRun() {
        String withBadBar = String.join(",",
                bar("2024-01-02", "187.15", "188.44", "183.89", "185.64", 82488700),
                bar("2024-01-03", "184.22", "180.00", "183.43", "184.25", 58414500)); // high < low
        stubBars(page(withBadBar, MSFT_3_DAYS, null));

        IngestionResult result = ingest();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.barsReceived()).isEqualTo(5);
        assertThat(result.barsRejected()).isEqualTo(1);
        assertThat(result.barsUpserted()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT bars_rejected FROM ingestion_runs").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void retriesARateLimitedRequestAndRecordsTheRetry() {
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).inScenario("429").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"message\":\"too many requests.\"}"))
                .willSetStateTo("ok"));
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).inScenario("429").whenScenarioStateIs("ok")
                .willReturn(okJson(page(AAPL_3_DAYS, MSFT_3_DAYS, null))));

        IngestionResult result = ingest();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.httpRetries()).isEqualTo(1);
        assertThat(count("bars")).isEqualTo(6);
        assertThat(jdbc.sql("SELECT http_retries FROM ingestion_runs").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aRunThatKeepsFailingIsLoggedAsFailedWithTheError() {
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).willReturn(aResponse().withStatus(503).withBody("upstream down")));

        IngestionResult result = ingest();

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).contains("503").contains("upstream down");
        Map<String, Object> run = jdbc.sql("SELECT status, error, http_retries, finished_at FROM ingestion_runs")
                .query().singleRow();
        assertThat(run.get("status")).isEqualTo("failed");
        assertThat((String) run.get("error")).contains("503");
        assertThat(run.get("http_retries")).isEqualTo(2); // max-attempts is 3 in the test profile
        assertThat(run.get("finished_at")).isNotNull();
    }

    @Test
    void pagesWrittenBeforeAFailureAreKept() {
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("page_token", absent())
                .willReturn(okJson(page(AAPL_3_DAYS, "", "page-2"))));
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("page_token", equalTo("page-2"))
                .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"invalid page token\"}")));

        IngestionResult result = ingest();

        assertThat(result.succeeded()).isFalse();
        assertThat(result.barsUpserted()).isEqualTo(3);
        assertThat(count("bars")).isEqualTo(3);
    }

    @Test
    void storesSplitsThatHaveTakenEffectAndIgnoresAnnouncedFutureOnes() {
        alpaca.resetMappings();
        alpaca.stubFor(get(urlPathEqualTo("/v1/corporate-actions")).willReturn(okJson("""
                {"corporate_actions":{
                  "forward_splits":[{"symbol":"AAPL","ex_date":"2024-01-03","old_rate":1,"new_rate":4,"process_date":"2024-01-03","id":"a","cusip":"c"}],
                  "reverse_splits":[{"symbol":"MSFT","ex_date":"2099-01-02","old_rate":10,"new_rate":1,"process_date":"2099-01-02","id":"b","cusip":"d"}]},
                 "next_page_token":null}""")));
        stubBars(page(AAPL_3_DAYS, MSFT_3_DAYS, null));

        IngestionResult first = ingest();
        IngestionResult second = ingest();

        assertThat(first.splitsUpserted()).isEqualTo(1);
        assertThat(second.splitsUpserted()).isZero();
        assertThat(jdbc.sql("SELECT s.ticker || ' ' || p.ex_date || ' ' || p.new_rate::int || ':' || p.old_rate::int FROM splits p JOIN symbols s ON s.id = p.symbol_id")
                .query(String.class).list()).containsExactly("AAPL 2024-01-03 4:1");
    }
}
