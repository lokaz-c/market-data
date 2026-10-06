package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The rate limiter in the running app (it is off in the other tests): the two filters together, the draft
 * RateLimit fields on real responses, and the headers a browser on another origin may read.
 */
@TestPropertySource(properties = {
        "market-data.api.rate-limit.enabled=true",
        "market-data.api.rate-limit.capacity=3",
        "market-data.api.cors-allowed-origins=https://ui.example"})
class RateLimitIT extends ApiTest {

    @Test
    void anonymousAndUnscopedKeysShareTheIpLimitAndTheRateLimitScopeLiftsIt() {
        insertSymbol("SYNA", "synthetic");
        String path = "/v1/splits/SYNA";

        for (int left = 2; left >= 0; left--) {
            Response ok = get(path);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.header("RateLimit-Policy")).isEqualTo("\"per-ip\";q=3;w=3");
            assertThat(ok.header("RateLimit")).startsWith("\"per-ip\";r=" + left + ";t=");
            assertThat(ok.header("X-RateLimit-Remaining")).isEqualTo(Integer.toString(left));
        }
        Response limited = get(path);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("RateLimit")).isEqualTo("\"per-ip\";r=0;t=" + limited.header("Retry-After"));

        // A key without the rate-limit scope is limited like an anonymous request from the same address.
        assertThat(getWithKey(path, ALPACA_DATA_KEY).status()).isEqualTo(429);
        // The rate-limit scope is not limited, and its responses carry no RateLimit fields.
        Response scoped = getWithKey(path, RATE_LIMIT_KEY);
        assertThat(scoped.status()).isEqualTo(200);
        assertThat(scoped.header("RateLimit")).isNull();
        assertThat(scoped.header("RateLimit-Policy")).isNull();
    }

    @Test
    void aBrowserOnAnAllowedOriginCanReadTheRateLimitAndEtagHeaders() {
        insertSymbol("SYNA", "synthetic");

        Response response = get("/v1/splits/SYNA", Map.of("Origin", "https://ui.example",
                ApiAccessFilter.API_KEY_HEADER, RATE_LIMIT_KEY));

        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("https://ui.example");
        assertThat(response.header("Access-Control-Expose-Headers"))
                .contains("ETag", "Retry-After", "RateLimit", "RateLimit-Policy", "X-RateLimit-Remaining");
    }
}
