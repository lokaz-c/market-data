package com.lokaz.marketdata.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.lokaz.marketdata.IntegrationTest;
import com.lokaz.marketdata.alpaca.Adjustment;
import com.lokaz.marketdata.alpaca.AlpacaBar;
import com.lokaz.marketdata.alpaca.AlpacaClient;

/**
 * Checks bars_split_adjusted against Alpaca's own adjustment=split bars. The hand-built fixture set always
 * runs; the recorded set runs once scripts/record-fixtures.sh has saved real responses.
 */
class SplitAdjustedViewIT extends IntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2020, 8, 24);
    private static final LocalDate TO = LocalDate.of(2020, 9, 4);
    /** Alpaca returns prices as JSON doubles; anything within a hundredth of a cent is the same price. */
    private static final BigDecimal PRICE_TOLERANCE = new BigDecimal("0.0001");

    @Autowired
    IngestionService ingestion;

    @Autowired
    AlpacaClient alpacaClient;

    record AdjustedRow(String ticker, LocalDate ts, BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, long volume, BigDecimal vwap) {
    }

    @ParameterizedTest(name = "{0} fixtures")
    @ValueSource(strings = {"hand-built", "recorded"})
    void viewReproducesAlpacaSplitAdjustedBars(String fixtureSet) throws IOException {
        String dir = "fixtures/alpaca/" + fixtureSet + "/";
        assumeTrue(new ClassPathResource(dir + "bars_raw.json").exists(),
                () -> "No " + fixtureSet + " fixtures yet (run scripts/record-fixtures.sh)");

        alpaca.stubFor(get(urlPathEqualTo("/v1/corporate-actions")).willReturn(okJson(read(dir + "corporate_actions.json"))));
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("adjustment", equalTo("raw"))
                .willReturn(okJson(read(dir + "bars_raw.json"))));
        alpaca.stubFor(get(urlPathEqualTo("/v2/stocks/bars")).withQueryParam("adjustment", equalTo("split"))
                .willReturn(okJson(read(dir + "bars_split.json"))));

        IngestionResult result = ingestion.ingest(
                new IngestionRequest(IngestionKind.BACKFILL, List.of("AAPL", "TSLA"), FROM, TO));
        assertThat(result.succeeded()).as(result.error()).isTrue();
        assertThat(result.splitsUpserted()).isPositive();

        Map<String, List<AlpacaBar>> expected = alpacaClient.fetchDailyBars(List.of("AAPL", "TSLA"), FROM, TO,
                Adjustment.SPLIT);
        List<AdjustedRow> actual = jdbc.sql("""
                SELECT s.ticker, a.ts, a.open, a.high, a.low, a.close, a.volume, a.vwap
                FROM bars_split_adjusted a JOIN symbols s ON s.id = a.symbol_id
                ORDER BY s.ticker, a.ts""").query(AdjustedRow.class).list();

        int expectedCount = expected.values().stream().mapToInt(List::size).sum();
        assertThat(actual).hasSize(expectedCount);
        for (AdjustedRow row : actual) {
            AlpacaBar want = expected.get(row.ticker()).stream()
                    .filter(b -> b.sessionDate().equals(row.ts())).findFirst().orElseThrow();
            String where = row.ticker() + " " + row.ts();
            assertClose(where + " open", row.open(), want.open());
            assertClose(where + " high", row.high(), want.high());
            assertClose(where + " low", row.low(), want.low());
            assertClose(where + " close", row.close(), want.close());
            assertClose(where + " vwap", row.vwap(), want.vwap());
            assertThat(Math.abs(row.volume() - want.volume())).as(where + " volume").isLessThanOrEqualTo(1);
        }
        // The fixture straddles the split: the raw series jumps, the adjusted one must not.
        assertThat(actual).anySatisfy(r -> assertThat(r.ticker()).isEqualTo("AAPL"));
    }

    private static void assertClose(String what, BigDecimal actual, BigDecimal expected) {
        assertThat(actual.subtract(expected).abs()).as("%s: view %s vs Alpaca %s", what, actual, expected)
                .isLessThanOrEqualTo(PRICE_TOLERANCE);
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    // The tests below insert rows directly to pin down the view's rules.

    private int symbol(String ticker) {
        return jdbc.sql("INSERT INTO symbols (ticker) VALUES (:t) RETURNING id").param("t", ticker).query(Integer.class).single();
    }

    private void bar(int symbolId, String date, String close, long volume) {
        jdbc.sql("""
                INSERT INTO bars (symbol_id, ts, open, high, low, close, volume, trade_count, vwap)
                VALUES (:id, CAST(:ts AS date), CAST(:c AS numeric), CAST(:c AS numeric), CAST(:c AS numeric),
                        CAST(:c AS numeric), :v, 1, CAST(:c AS numeric))""")
                .param("id", symbolId).param("ts", date).param("c", close).param("v", volume).update();
    }

    private void split(int symbolId, String exDate, int oldRate, int newRate) {
        jdbc.sql("INSERT INTO splits (symbol_id, ex_date, old_rate, new_rate, source) VALUES (:id, CAST(:d AS date), :o, :n, 'alpaca')")
                .param("id", symbolId).param("d", exDate).param("o", oldRate).param("n", newRate).update();
    }

    private Map<String, Object> adjusted(int symbolId, String date) {
        return jdbc.sql("SELECT close, volume, split_factor FROM bars_split_adjusted WHERE symbol_id = :id AND ts = CAST(:d AS date)")
                .param("id", symbolId).param("d", date).query().singleRow();
    }

    @Test
    void splitsCompoundAndABarOnTheExDateIsAlreadyPostSplit() {
        int id = symbol("NVDA");
        split(id, "2021-07-20", 1, 4);
        split(id, "2024-06-10", 1, 10);
        bar(id, "2021-07-19", "744.00", 100);   // before both splits: / 40
        bar(id, "2021-07-20", "187.00", 400);   // on the first ex-date: / 10 only
        bar(id, "2024-06-07", "1208.88", 50);   // before the second split: / 10
        bar(id, "2024-06-10", "121.79", 500);   // on the last ex-date: unchanged

        assertThat((BigDecimal) adjusted(id, "2021-07-19").get("close")).isEqualByComparingTo("18.6");
        assertThat(adjusted(id, "2021-07-19").get("volume")).isEqualTo(4000L);
        assertThat((BigDecimal) adjusted(id, "2021-07-20").get("close")).isEqualByComparingTo("18.7");
        assertThat((BigDecimal) adjusted(id, "2024-06-07").get("close")).isEqualByComparingTo("120.888");
        assertThat((BigDecimal) adjusted(id, "2024-06-10").get("close")).isEqualByComparingTo("121.79");
        assertThat((BigDecimal) adjusted(id, "2024-06-10").get("split_factor")).isEqualByComparingTo("1");
    }

    @Test
    void reverseSplitsAdjustUpAndOffsettingSplitsCancelExactly() {
        int id = symbol("REV");
        split(id, "2024-01-10", 2, 3);  // 3-for-2
        split(id, "2024-02-10", 3, 2);  // 2-for-3 reverse: cancels the first exactly
        split(id, "2024-03-10", 10, 1); // 1-for-10 reverse
        bar(id, "2024-01-09", "5.00", 1000);
        bar(id, "2024-03-09", "5.00", 1000);

        // 3/2 * 2/3 * 1/10: an exact NUMERIC product gives exactly 1/10, not 0.0999999...
        assertThat((BigDecimal) adjusted(id, "2024-01-09").get("split_factor")).isEqualByComparingTo("0.1");
        assertThat((BigDecimal) adjusted(id, "2024-01-09").get("close")).isEqualByComparingTo("50");
        assertThat(adjusted(id, "2024-01-09").get("volume")).isEqualTo(100L);
        assertThat((BigDecimal) adjusted(id, "2024-03-09").get("close")).isEqualByComparingTo("50");
    }

    @Test
    void splitsOnlyAffectTheirOwnSymbol() {
        int split = symbol("SPLT");
        int plain = symbol("PLAIN");
        split(split, "2024-01-10", 1, 2);
        bar(split, "2024-01-09", "100.00", 10);
        bar(plain, "2024-01-09", "100.00", 10);

        assertThat((BigDecimal) adjusted(split, "2024-01-09").get("close")).isEqualByComparingTo("50");
        assertThat((BigDecimal) adjusted(plain, "2024-01-09").get("close")).isEqualByComparingTo("100");
        assertThat(count("bars_split_adjusted")).isEqualTo(2);
    }
}
