package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

class ApiKeysTest {

    private static ApiKeys keys(String... sha256) {
        return new ApiKeys(new ApiProperties(List.of(sha256), List.of("synthetic"), 100, List.of(),
                new ApiProperties.RateLimit(true, 30, 60)));
    }

    private static String sha256Hex(String key) {
        return HexFormat.of().formatHex(ApiKeys.sha256(key));
    }

    @Test
    void acceptsAKeyWhoseDigestIsConfigured() {
        // Upper-case hex is accepted too.
        ApiKeys keys = keys(sha256Hex("other-key"), sha256Hex("my-key").toUpperCase());
        assertThat(keys.isValid("my-key")).isTrue();
        assertThat(keys.isValid("other-key")).isTrue();
    }

    @Test
    void rejectsWrongMissingAndBlankKeys() {
        ApiKeys keys = keys(sha256Hex("my-key"));
        assertThat(keys.isValid("my-key2")).isFalse();
        assertThat(keys.isValid(null)).isFalse();
        assertThat(keys.isValid("  ")).isFalse();
    }

    @Test
    void noConfiguredKeysMeansNothingIsValid() {
        assertThat(keys().isValid("anything")).isFalse();
        assertThat(keys("", " ").isValid("")).isFalse();
    }
}
