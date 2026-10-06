package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** Compares /v1/indicators and /v1/levels with the plain-Java reference and with hand-computed values. */
class IndicatorsIT extends ApiTest {

    private static final LocalDate START = LocalDate.of(2023, 1, 2);
    /** SQL rounds SMA/ATR/pivots to 4 decimals and volatility to 6. */
    private static final double PRICE_TOLERANCE = 0.00006;
    private static final double VOL_TOLERANCE = 0.000001;

    private List<TestSeries.Bar> loadSeries(int count) {
        List<TestSeries.Bar> bars = TestSeries.weekdays(START, count);
        insertBars(insertSymbol("SYNT", "synthetic"), bars);
        return bars;
    }

    private static void assertNear(JsonNode node, Double expected, double tolerance, String what) {
        if (expected == null) {
            assertThat(node.isNull()).as(what + " should be null").isTrue();
        } else {
            assertThat(node.isNull()).as(what + " should not be null").isFalse();
            assertThat(node.doubleValue()).as(what).isCloseTo(expected, within(tolerance));
        }
    }

    @Test
    void everyIndicatorMatchesTheJavaReferenceOverAYearAndAHalf() {
        List<TestSeries.Bar> bars = loadSeries(400);
        LocalDate last = bars.getLast().date();

        Response response = get("/v1/indicators/SYNT?from=" + START + "&to=" + last + "&limit=1000");

        assertThat(response.status()).isEqualTo(200);
        JsonNode rows = response.json().get("indicators");
        assertThat(rows.size()).isEqualTo(bars.size());
        assertThat(response.json().get("nextAfter").isNull()).isTrue();
        for (int i = 0; i < bars.size(); i++) {
            JsonNode row = rows.get(i);
            String day = bars.get(i).date().toString();
            assertThat(row.get("date").asString()).isEqualTo(day);
            assertNear(row.get("sma20"), IndicatorReference.sma(bars, i, 20), PRICE_TOLERANCE, day + " sma20");
            assertNear(row.get("sma50"), IndicatorReference.sma(bars, i, 50), PRICE_TOLERANCE, day + " sma50");
            assertNear(row.get("sma200"), IndicatorReference.sma(bars, i, 200), PRICE_TOLERANCE, day + " sma200");
            assertNear(row.get("volatility20"), IndicatorReference.volatility20(bars, i), VOL_TOLERANCE, day + " vol20");
            assertNear(row.get("atr14"), IndicatorReference.atr14(bars, i), PRICE_TOLERANCE, day + " atr14");
            assertNear(row.get("pivot"), i == 0 ? null : IndicatorReference.pivot(bars.get(i - 1)), PRICE_TOLERANCE,
                    day + " pivot");
            boolean fullYear = !bars.get(i).date().isBefore(START.plusDays(364));
            if (fullYear) {
                assertThat(decimal(row.get("high52w"))).as(day + " high52w")
                        .isEqualByComparingTo(IndicatorReference.high52w(bars, i));
                assertThat(decimal(row.get("low52w"))).as(day + " low52w")
                        .isEqualByComparingTo(IndicatorReference.low52w(bars, i));
            } else {
                assertThat(row.get("high52w").isNull()).as(day + " high52w before a full year").isTrue();
            }
        }
    }

    @Test
    void laterPagesStillSeeTheHistoryTheirWindowsNeed() {
        List<TestSeries.Bar> bars = loadSeries(400);
        // Ask only for the last 30 days: SMA200 must still use 200 days of history from before `from`.
        int firstIndex = bars.size() - 30;
        Response response = get("/v1/indicators/SYNT?from=" + bars.get(firstIndex).date() + "&to="
                + bars.getLast().date() + "&limit=10");

        JsonNode page = response.json();
        assertThat(page.get("indicators").size()).isEqualTo(10);
        assertThat(page.get("nextAfter").asString()).isEqualTo(bars.get(firstIndex + 9).date().toString());
        assertNear(page.get("indicators").get(0).get("sma200"), IndicatorReference.sma(bars, firstIndex, 200),
                PRICE_TOLERANCE, "sma200 on the first day of the page");

        Response next = get("/v1/indicators/SYNT?from=" + bars.get(firstIndex).date() + "&to=" + bars.getLast().date()
                + "&limit=10&after=" + page.get("nextAfter").asString());
        assertThat(next.json().get("indicators").get(0).get("date").asString())
                .isEqualTo(bars.get(firstIndex + 10).date().toString());
        assertNear(next.json().get("indicators").get(0).get("atr14"), IndicatorReference.atr14(bars, firstIndex + 10),
                PRICE_TOLERANCE, "atr14 on the second page");
    }

    @Test
    void handComputedTrueRangeAndPivots() {
        int id = insertSymbol("HAND", "synthetic");
        insertBars(id, List.of(
                bar("2024-01-02", "9", "10", "8", "9"),
                bar("2024-01-03", "9.5", "12", "9", "11"),
                bar("2024-01-04", "11", "11.5", "10", "10.5")));

        JsonNode rows = get("/v1/indicators/HAND?from=2024-01-02&to=2024-01-04").json().get("indicators");

        // Pivot for 01-03 from 01-02: P = (10 + 8 + 9) / 3 = 9, R1 = 2P - L = 10, S1 = 2P - H = 8.
        assertThat(decimal(rows.get(1).get("pivot"))).isEqualByComparingTo("9");
        assertThat(decimal(rows.get(1).get("r1"))).isEqualByComparingTo("10");
        assertThat(decimal(rows.get(1).get("s1"))).isEqualByComparingTo("8");
        // Pivot for 01-04 from 01-03: P = (12 + 9 + 11) / 3 = 10.6667, R1 = 21.3333 - 9, S1 = 21.3333 - 12.
        assertThat(decimal(rows.get(2).get("pivot"))).isEqualByComparingTo("10.6667");
        assertThat(decimal(rows.get(2).get("r1"))).isEqualByComparingTo("12.3333");
        assertThat(decimal(rows.get(2).get("s1"))).isEqualByComparingTo("9.3333");
        // Not enough history for any moving window yet.
        assertThat(rows.get(2).get("sma20").isNull()).isTrue();
        assertThat(rows.get(2).get("atr14").isNull()).isTrue();

        // Levels from the 01-04 session (H 11.5, L 10, C 10.5): P = 32/3.
        JsonNode levels = get("/v1/levels/HAND").json();
        assertThat(levels.get("asOf").asString()).isEqualTo("2024-01-04");
        JsonNode p = levels.get("pivots");
        assertThat(p.get("basedOn").asString()).isEqualTo("2024-01-04");
        assertThat(decimal(p.get("p"))).isEqualByComparingTo("10.6667");
        assertThat(decimal(p.get("r1"))).isEqualByComparingTo("11.3333"); // 2P - L
        assertThat(decimal(p.get("s1"))).isEqualByComparingTo("9.8333");  // 2P - H
        assertThat(decimal(p.get("r2"))).isEqualByComparingTo("12.1667"); // P + (H - L)
        assertThat(decimal(p.get("s2"))).isEqualByComparingTo("9.1667");  // P - (H - L)
        assertThat(decimal(p.get("r3"))).isEqualByComparingTo("12.8333"); // H + 2(P - L)
        assertThat(decimal(p.get("s3"))).isEqualByComparingTo("8.3333");  // L - 2(H - P)
        // Three sessions are not enough for 20/50-day or 52-week ranges.
        assertThat(levels.get("range20d").get("high").isNull()).isTrue();
        assertThat(levels.get("range52w").get("low").isNull()).isTrue();
    }

    @Test
    void levelsMatchTheReferenceForTheLatestAndAnEarlierSession() {
        List<TestSeries.Bar> bars = loadSeries(400);
        int last = bars.size() - 1;

        JsonNode levels = get("/v1/levels/SYNT").json();
        assertLevels(levels, bars, last);

        // A Saturday resolves to the Friday before it.
        int friday = 300;
        while (bars.get(friday).date().getDayOfWeek() != java.time.DayOfWeek.FRIDAY) {
            friday++;
        }
        JsonNode earlier = get("/v1/levels/SYNT?asOf=" + bars.get(friday).date().plusDays(1)).json();
        assertThat(earlier.get("asOf").asString()).isEqualTo(bars.get(friday).date().toString());
        assertLevels(earlier, bars, friday);
    }

    private static void assertLevels(JsonNode levels, List<TestSeries.Bar> bars, int i) {
        assertThat(levels.get("asOf").asString()).isEqualTo(bars.get(i).date().toString());
        assertThat(decimal(levels.get("close"))).isEqualByComparingTo(bars.get(i).close());
        assertThat(decimal(levels.get("range20d").get("high"))).isEqualByComparingTo(IndicatorReference.highN(bars, i, 20));
        assertThat(decimal(levels.get("range20d").get("low"))).isEqualByComparingTo(IndicatorReference.lowN(bars, i, 20));
        assertThat(decimal(levels.get("range50d").get("high"))).isEqualByComparingTo(IndicatorReference.highN(bars, i, 50));
        assertThat(decimal(levels.get("range50d").get("low"))).isEqualByComparingTo(IndicatorReference.lowN(bars, i, 50));
        assertThat(decimal(levels.get("range52w").get("high"))).isEqualByComparingTo(IndicatorReference.high52w(bars, i));
        assertThat(decimal(levels.get("range52w").get("low"))).isEqualByComparingTo(IndicatorReference.low52w(bars, i));
        double p = IndicatorReference.pivot(bars.get(i));
        assertThat(levels.get("pivots").get("p").doubleValue()).isCloseTo(p, within(PRICE_TOLERANCE));
        assertThat(levels.get("pivots").get("r1").doubleValue())
                .isCloseTo(2 * p - bars.get(i).low().doubleValue(), within(PRICE_TOLERANCE));
    }

    @Test
    void indicatorsUseSplitAdjustedPrices() {
        int id = insertSymbol("SPLT", "synthetic");
        List<TestSeries.Bar> adjusted = TestSeries.weekdays(START, 60);
        LocalDate exDate = adjusted.get(40).date();
        // Raw prices before a 4-for-1 split are four times the adjusted ones.
        List<TestSeries.Bar> raw = adjusted.stream().map(b -> b.date().isBefore(exDate)
                ? new TestSeries.Bar(b.date(), x4(b.open()), x4(b.high()), x4(b.low()), x4(b.close()), b.volume() / 4)
                : b).toList();
        insertBars(id, raw);
        insertSplit(id, exDate, 1, 4);

        JsonNode rows = get("/v1/indicators/SPLT?from=" + START + "&to=" + adjusted.getLast().date() + "&limit=100")
                .json().get("indicators");

        int i = 45; // its 20-day window spans the ex-date
        assertNear(rows.get(i).get("sma20"), IndicatorReference.sma(adjusted, i, 20), PRICE_TOLERANCE, "sma20 across split");
        assertNear(rows.get(i).get("atr14"), IndicatorReference.atr14(adjusted, i), PRICE_TOLERANCE, "atr14 across split");
    }

    private static BigDecimal x4(BigDecimal v) {
        return v.multiply(BigDecimal.valueOf(4));
    }

    private static TestSeries.Bar bar(String date, String o, String h, String l, String c) {
        return new TestSeries.Bar(LocalDate.parse(date), new BigDecimal(o), new BigDecimal(h), new BigDecimal(l),
                new BigDecimal(c), 1000);
    }
}
