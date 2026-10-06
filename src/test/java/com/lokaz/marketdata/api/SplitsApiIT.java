package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class SplitsApiIT extends ApiTest {

    @Test
    void listsSplitsOldestFirstWithBothRates() {
        int id = insertSymbol("SPLT", "synthetic");
        insertSplit(id, LocalDate.of(2024, 6, 3), 10, 1);
        insertSplit(id, LocalDate.of(2020, 8, 31), 1, 4);

        Response response = get("/v1/splits/splt");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).contains("max-age=300");
        JsonNode body = response.json();
        assertThat(body.get("ticker").asString()).isEqualTo("SPLT");
        assertThat(body.get("source").asString()).isEqualTo("synthetic");
        JsonNode splits = body.get("splits");
        assertThat(splits).hasSize(2);
        assertThat(splits.get(0).get("exDate").asString()).isEqualTo("2020-08-31");
        assertThat(decimal(splits.get(0).get("oldRate"))).isEqualByComparingTo("1");
        assertThat(decimal(splits.get(0).get("newRate"))).isEqualByComparingTo("4");
        assertThat(splits.get(0).get("newRate").toString()).isEqualTo("4");
        assertThat(splits.get(1).get("exDate").asString()).isEqualTo("2024-06-03");
        assertThat(decimal(splits.get(1).get("oldRate"))).isEqualByComparingTo("10");
    }

    @Test
    void aSymbolWithoutSplitsHasAnEmptyList() {
        insertSymbol("SYNA", "synthetic");
        assertThat(get("/v1/splits/SYNA").json().get("splits")).isEmpty();
    }

    @Test
    void alpacaSplitsFollowTheSameVisibilityRulesAsBars() {
        insertSplit(insertSymbol("AAPL", "alpaca"), LocalDate.of(2020, 8, 31), 1, 4);

        assertThat(get("/v1/splits/AAPL").status()).isEqualTo(404);
        assertThat(get("/v1/splits/NOPE").status()).isEqualTo(404);
        assertThat(getWithKey("/v1/splits/AAPL", API_KEY).json().get("splits")).hasSize(1);
    }
}
