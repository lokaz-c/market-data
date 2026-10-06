package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each scope unlocks one thing; the keys come from API_KEYS (see IntegrationTest). */
class ScopesIT extends ApiTest {

    private static final String EXPORT_SYNA = "/v1/export/bars.csv?symbols=SYNA&from=2024-01-01&to=2024-12-31";

    @BeforeEach
    void data() {
        insertBars(insertSymbol("SYNA", "synthetic"), TestSeries.weekdays(LocalDate.of(2024, 1, 2), 3));
        insertBars(insertSymbol("AAPL", "alpaca"), TestSeries.weekdays(LocalDate.of(2024, 1, 2), 3));
    }

    @Test
    void aRateLimitKeySeesOnlyPublicDataAndCannotExport() {
        assertThat(getWithKey("/v1/bars/SYNA", RATE_LIMIT_KEY).status()).isEqualTo(200);
        assertThat(getWithKey("/v1/bars/AAPL", RATE_LIMIT_KEY).status()).isEqualTo(404);
        assertThat(getWithKey("/v1/symbols", RATE_LIMIT_KEY).json().get("symbols")).hasSize(1);

        Response export = getWithKey(EXPORT_SYNA, RATE_LIMIT_KEY);
        assertThat(export.status()).isEqualTo(403);
        assertThat(export.header("Content-Type")).startsWith("application/problem+json");
        assertThat(export.json().get("detail").asString()).contains("export scope");
    }

    @Test
    void anAlpacaDataKeySeesAlpacaSymbols() {
        assertThat(getWithKey("/v1/bars/AAPL", ALPACA_DATA_KEY).status()).isEqualTo(200);
        assertThat(getWithKey("/v1/splits/AAPL", ALPACA_DATA_KEY).status()).isEqualTo(200);
        assertThat(getWithKey("/v1/symbols", ALPACA_DATA_KEY).json().get("symbols")).hasSize(2);
        assertThat(getWithKey(EXPORT_SYNA, ALPACA_DATA_KEY).status()).isEqualTo(403);
    }

    @Test
    void anExportKeyExportsOnlyWhatItCanSee() {
        Response synthetic = getWithKey(EXPORT_SYNA, EXPORT_KEY);
        assertThat(synthetic.status()).isEqualTo(200);
        assertThat(synthetic.body().lines()).hasSize(1 + 3);
        assertThat(getWithKey(EXPORT_SYNA.replace("SYNA", "AAPL"), EXPORT_KEY).status()).isEqualTo(404);
    }

    @Test
    void anyValidKeyMakesResponsesPrivate() {
        assertThat(getWithKey("/v1/bars/SYNA", RATE_LIMIT_KEY).header("Cache-Control")).contains("private");
        assertThat(get("/v1/bars/SYNA").header("Cache-Control")).contains("public");
    }

    @Test
    void theOriginalKeySettingStillGrantsEverything() {
        assertThat(getWithKey("/v1/bars/AAPL", API_KEY).status()).isEqualTo(200);
        assertThat(getWithKey(EXPORT_SYNA.replace("SYNA", "AAPL,SYNA"), API_KEY).status()).isEqualTo(200);
    }
}
