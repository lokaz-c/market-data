package com.lokaz.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class ApplicationIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void healthIsUpWithTheDatabaseConnected() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessAndReadinessProbesAreUp() throws Exception {
        var client = HttpClient.newHttpClient();
        var readiness = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/actuator/health/readiness")).build(), HttpResponse.BodyHandlers.ofString());
        var liveness = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/actuator/health/liveness")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("UP");
    }
}
