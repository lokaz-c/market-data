package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenApiIT extends ApiTest {

    @Test
    void openApiDocumentDescribesTheEndpointsAndTheApiKeyScheme() {
        Response response = get("/v3/api-docs");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("/v1/symbols", "/v1/bars/{ticker}", "/v1/indicators/{ticker}", "/v1/levels/{ticker}",
                        "/v1/export/bars.csv", "/v1/splits/{ticker}", "X-API-Key", "If-None-Match");
    }

    @Test
    void swaggerUiIsServed() {
        Response response = get("/swagger-ui/index.html");
        assertThat(response.status()).isEqualTo(200);
    }
}
