package com.lokaz.marketdata;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Base for tests that need the full application: a real PostgreSQL (Testcontainers) with Flyway migrations
 * applied, and WireMock standing in for Alpaca. Tables are emptied before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final WireMockServer alpaca = new WireMockServer(options().dynamicPort());

    static {
        alpaca.start();
    }

    /**
     * The API key most tests send. It is configured the original way (API_KEY_SHA256), so it has every scope; only
     * its SHA-256 digest is configured, as in production.
     */
    public static final String TEST_API_KEY = "test-api-key";
    /** Keys configured through API_KEYS with one scope each. */
    public static final String RATE_LIMIT_KEY = "rate-limit-key";
    public static final String ALPACA_DATA_KEY = "alpaca-data-key";
    public static final String EXPORT_KEY = "export-key";

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("market-data.alpaca.base-url", alpaca::baseUrl);
        registry.add("market-data.api.key-sha256", () -> sha256Hex(TEST_API_KEY));
        registry.add("market-data.api.keys", () -> sha256Hex(RATE_LIMIT_KEY) + ":rate-limit;"
                + sha256Hex(ALPACA_DATA_KEY) + ":alpaca-data;" + sha256Hex(EXPORT_KEY) + ":export");
    }

    protected static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetState() {
        alpaca.resetAll();
        jdbc.sql("TRUNCATE bars, splits, ingestion_runs, symbols RESTART IDENTITY CASCADE").update();
    }

    protected int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
