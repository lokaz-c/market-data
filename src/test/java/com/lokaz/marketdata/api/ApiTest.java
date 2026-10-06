package com.lokaz.marketdata.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.lokaz.marketdata.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Integration tests that call the running server over HTTP and insert bars directly. */
abstract class ApiTest extends IntegrationTest {

    static final String API_KEY = TEST_API_KEY;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    record Response(int status, HttpHeaders headers, String body, JsonMapper mapper) {
        JsonNode json() {
            return mapper.readTree(body);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    Response get(String path) {
        return send(path, null);
    }

    Response getWithKey(String path, String key) {
        return send(path, key);
    }

    private Response send(String path, String key) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (key != null) {
            request.header(ApiAccessFilter.API_KEY_HEADER, key);
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers(), response.body(), json);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    int insertSymbol(String ticker, String source) {
        return jdbc.sql("INSERT INTO symbols (ticker, source) VALUES (:t, :s) RETURNING id")
                .param("t", ticker).param("s", source).query(Integer.class).single();
    }

    void insertBars(int symbolId, List<TestSeries.Bar> bars) {
        for (TestSeries.Bar b : bars) {
            jdbc.sql("""
                    INSERT INTO bars (symbol_id, ts, open, high, low, close, volume, trade_count, vwap)
                    VALUES (:id, :ts, :o, :h, :l, :c, :v, 1, :c)""")
                    .param("id", symbolId).param("ts", b.date()).param("o", b.open()).param("h", b.high())
                    .param("l", b.low()).param("c", b.close()).param("v", b.volume()).update();
        }
    }

    void insertSplit(int symbolId, LocalDate exDate, int oldRate, int newRate) {
        jdbc.sql("INSERT INTO splits (symbol_id, ex_date, old_rate, new_rate, source) VALUES (:id, :d, :o, :n, 'synthetic')")
                .param("id", symbolId).param("d", exDate).param("o", oldRate).param("n", newRate).update();
    }

    static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }
}
