package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ExportIT extends ApiTest {

    @Test
    void exportRequiresAnApiKey() {
        Response response = get("/v1/export/bars.csv?symbols=SYNA&from=2024-01-01&to=2024-12-31");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.json().get("detail").asString()).contains("X-API-Key");
    }

    @Test
    void streamsCsvForSeveralSymbolsInRequestOrderIncludingNonPublicSources() {
        insertBars(insertSymbol("SYNA", "synthetic"), TestSeries.weekdays(LocalDate.of(2024, 1, 2), 3));
        int aapl = insertSymbol("AAPL", "alpaca");
        insertBars(aapl, TestSeries.weekdays(LocalDate.of(2024, 1, 2), 2));
        jdbc.sql("UPDATE bars SET feed = 'iex' WHERE symbol_id = :id").param("id", aapl).update();

        Response response = getWithKey("/v1/export/bars.csv?symbols=aapl,SYNA&from=2024-01-01&to=2024-12-31", API_KEY);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/csv");
        List<String> lines = response.body().lines().toList();
        assertThat(lines.getFirst()).isEqualTo("ticker,date,open,high,low,close,volume,source,feed");
        assertThat(lines).hasSize(1 + 2 + 3);
        assertThat(lines.get(1)).startsWith("AAPL,2024-01-02,").endsWith(",alpaca,iex");
        assertThat(lines.get(3)).startsWith("SYNA,2024-01-02,").endsWith(",synthetic,");
        assertThat(lines.get(5)).startsWith("SYNA,2024-01-04,");
        // Every row has the header's nine fields, including an empty feed.
        assertThat(lines).allSatisfy(line -> assertThat(line.split(",", -1)).hasSize(9));
    }

    @Test
    void rejectsTooManySymbolsAndUnknownOnesBeforeStreaming() {
        // 101 distinct tickers: S001,S002,...
        var tickers = new StringBuilder();
        for (int i = 1; i <= 101; i++) {
            tickers.append(i == 1 ? "" : ",").append("S").append(String.format("%03d", i));
        }
        Response tooManyResponse = getWithKey("/v1/export/bars.csv?symbols=" + tickers + "&from=2024-01-01&to=2024-12-31", API_KEY);
        assertThat(tooManyResponse.status()).isEqualTo(400);
        assertThat(tooManyResponse.json().get("detail").asString()).contains("100");

        Response unknown = getWithKey("/v1/export/bars.csv?symbols=NOPE&from=2024-01-01&to=2024-12-31", API_KEY);
        assertThat(unknown.status()).isEqualTo(404);
    }
}
