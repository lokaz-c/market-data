package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** ETag / If-None-Match on /v1/bars: 304 while the symbol's data is unchanged, 200 once it changes. */
class ConditionalBarsIT extends ApiTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);
    private static final String PATH = "/v1/bars/SYNA?from=2024-01-01&to=2024-12-31";

    private int symbolId;
    private List<TestSeries.Bar> bars;

    private void load() {
        bars = TestSeries.weekdays(START, 20);
        symbolId = insertSymbol("SYNA", "synthetic");
        insertBars(symbolId, bars.subList(0, 19));
    }

    private Response ifNoneMatch(String path, String etag) {
        return get(path, Map.of("If-None-Match", etag));
    }

    @Test
    void aMatchingIfNoneMatchGetsA304WithNoBodyAndTheCachingHeaders() {
        load();
        Response first = get(PATH);
        String etag = first.header("ETag");
        assertThat(etag).startsWith("W/\"");

        Response again = ifNoneMatch(PATH, etag);

        assertThat(again.status()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(again.header("ETag")).isEqualTo(etag);
        assertThat(again.header("Cache-Control")).contains("max-age=300");
        assertThat(again.header("Vary")).contains(ApiAccessFilter.API_KEY_HEADER);
        // If-None-Match takes a list; a strong copy of the same tag matches under weak comparison.
        assertThat(ifNoneMatch(PATH, "\"other\", " + etag.substring(2)).status()).isEqualTo(304);
        assertThat(ifNoneMatch(PATH, "W/\"other\"").status()).isEqualTo(200);
    }

    @Test
    void aNewBarChangesTheEtag() {
        load();
        String etag = get(PATH).header("ETag");

        insertBars(symbolId, bars.subList(19, 20));
        Response after = ifNoneMatch(PATH, etag);

        assertThat(after.status()).isEqualTo(200);
        assertThat(after.header("ETag")).isNotEqualTo(etag);
        assertThat(after.json().get("bars").size()).isEqualTo(20);
    }

    @Test
    void aNewSplitChangesTheEtagBecauseItReBasesAdjustedPrices() {
        load();
        String etag = get(PATH).header("ETag");
        String before = get(PATH).json().get("bars").get(0).get("close").asString();

        insertSplit(symbolId, bars.get(10).date(), 1, 2);
        Response after = ifNoneMatch(PATH, etag);

        assertThat(after.status()).isEqualTo(200);
        assertThat(after.json().get("bars").get(0).get("close").asString()).isNotEqualTo(before);
    }

    @Test
    void theEtagDependsOnTheRequestAndOnlyOnThisSymbolsData() {
        load();
        int other = insertSymbol("OTHR", "synthetic");
        insertBars(other, bars.subList(0, 5));
        String etag = get(PATH).header("ETag");

        assertThat(get(PATH + "&adjustment=raw").header("ETag")).isNotEqualTo(etag);
        assertThat(get("/v1/bars/SYNA?last=5").header("ETag")).isNotEqualTo(etag);
        insertBars(other, bars.subList(5, 6));
        assertThat(ifNoneMatch(PATH, etag).status()).isEqualTo(304);
    }

    @Test
    void errorsCarryNoEtag() {
        Response response = get("/v1/bars/NOPE");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("ETag")).isNull();
    }
}
