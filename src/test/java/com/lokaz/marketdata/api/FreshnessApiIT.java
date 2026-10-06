package com.lokaz.marketdata.api;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.lokaz.marketdata.ingestion.IngestionKind;
import com.lokaz.marketdata.ingestion.IngestionRequest;
import com.lokaz.marketdata.ingestion.IngestionService;

import tools.jackson.databind.JsonNode;

/** What a client sees after real ingestion runs: freshness on /v1/symbols and ETags that track the data. */
class FreshnessApiIT extends ApiTest {

    private static final String BARS = """
            {"bars":{"AAPL":[
              {"t":"2024-01-02T05:00:00Z","o":187.15,"h":188.44,"l":183.89,"c":185.64,"v":82488700,"n":1,"vw":185.6},
              {"t":"2024-01-03T05:00:00Z","o":184.22,"h":185.88,"l":183.43,"c":%s,"v":58414500,"n":1,"vw":184.2}]},
             "next_page_token":null}""";

    @Autowired
    IngestionService ingestion;

    private long ingest(String close) {
        alpaca.resetMappings();
        alpaca.stubFor(WireMock.get(urlPathEqualTo("/v1/corporate-actions"))
                .willReturn(okJson("{\"corporate_actions\":{},\"next_page_token\":null}")));
        alpaca.stubFor(WireMock.get(urlPathEqualTo("/v2/stocks/bars")).willReturn(okJson(BARS.formatted(close))));
        return ingestion.ingest(new IngestionRequest(IngestionKind.DAILY, List.of("AAPL"), LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 1, 3))).runId();
    }

    @Test
    void symbolsReportTheFeedAndTheLastSuccessfulRun() {
        long runId = ingest("184.25");

        JsonNode aapl = getWithKey("/v1/symbols", API_KEY).json().get("symbols").get(0);

        OffsetDateTime finished = jdbc.sql("SELECT finished_at FROM ingestion_runs WHERE id = :id").param("id", runId)
                .query(OffsetDateTime.class).single();
        assertThat(aapl.get("ticker").asString()).isEqualTo("AAPL");
        assertThat(aapl.get("feed").asString()).isEqualTo("iex");
        assertThat(OffsetDateTime.parse(aapl.get("lastIngestedAt").asString())).isAtSameInstantAs(finished);
        assertThat(aapl.get("lastBar").asString()).isEqualTo("2024-01-03");
    }

    @Test
    void theBarsEtagSurvivesAnIdenticalRunAndChangesWithACorrection() {
        ingest("184.25");
        String path = "/v1/bars/AAPL?from=2024-01-01&to=2024-01-31";
        String etag = getWithKey(path, API_KEY).header("ETag");
        Map<String, String> conditional = Map.of(ApiAccessFilter.API_KEY_HEADER, API_KEY, "If-None-Match", etag);

        ingest("184.25");
        assertThat(get(path, conditional).status()).isEqualTo(304);

        ingest("184.30");
        Response changed = get(path, conditional);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(decimal(changed.json().get("bars").get(1).get("close"))).isEqualByComparingTo("184.30");
    }
}
