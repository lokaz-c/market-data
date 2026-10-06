package com.lokaz.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeysTest {

    private static ApiKeys keys(List<String> legacySha256, String scoped) {
        return new ApiKeys(new ApiProperties(legacySha256, scoped, List.of("synthetic"), 100, List.of(),
                new ApiProperties.RateLimit(true, 30, 60)));
    }

    private static ApiKeys legacy(String... sha256) {
        return keys(List.of(sha256), "");
    }

    private static String sha256Hex(String key) {
        return HexFormat.of().formatHex(ApiKeys.sha256(key));
    }

    @Test
    void legacyDigestsKeepEveryScope() {
        // Upper-case hex is accepted too.
        ApiKeys keys = legacy(sha256Hex("other-key"), sha256Hex("my-key").toUpperCase());
        assertThat(keys.scopes("my-key")).contains(EnumSet.allOf(Scope.class));
        assertThat(keys.scopes("other-key")).contains(EnumSet.allOf(Scope.class));
    }

    @Test
    void scopedKeysGetExactlyTheirScopes() {
        ApiKeys keys = keys(List.of(), " " + sha256Hex("tradedesk") + ":rate-limit ; "
                + sha256Hex("quant") + ":rate-limit, alpaca-data,EXPORT;");

        assertThat(keys.scopes("tradedesk")).contains(EnumSet.of(Scope.RATE_LIMIT));
        assertThat(keys.scopes("quant")).contains(EnumSet.of(Scope.RATE_LIMIT, Scope.ALPACA_DATA, Scope.EXPORT));
    }

    @Test
    void bothSettingsCanBeUsedTogetherDuringAMigration() {
        ApiKeys keys = keys(List.of(sha256Hex("old")), sha256Hex("new") + ":export");
        assertThat(keys.scopes("old")).contains(EnumSet.allOf(Scope.class));
        assertThat(keys.scopes("new")).contains(EnumSet.of(Scope.EXPORT));
    }

    @Test
    void rejectsWrongMissingAndBlankKeys() {
        ApiKeys keys = keys(List.of(sha256Hex("my-key")), sha256Hex("scoped") + ":rate-limit");
        assertThat(keys.scopes("my-key2")).isEmpty();
        assertThat(keys.scopes(null)).isEmpty();
        assertThat(keys.scopes("  ")).isEmpty();
    }

    @Test
    void noConfiguredKeysMeansNothingIsValid() {
        assertThat(legacy().scopes("anything")).isEmpty();
        assertThat(keys(List.of("", " "), " ; ").scopes("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%s", "%s:", "%s:rate-limit,admin", "abc123:rate-limit", "%sff:export", "%s:export;%<s:rate-limit"})
    void aMalformedEntryStopsStartup(String template) {
        String entry = template.formatted(sha256Hex("k"));
        assertThatThrownBy(() -> keys(List.of(), entry)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API_KEY")
                .hasMessageNotContaining(sha256Hex("k"));
    }

    @Test
    void theSameDigestInBothSettingsStopsStartup() {
        assertThatThrownBy(() -> keys(List.of(sha256Hex("k")), sha256Hex("k") + ":rate-limit"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }

    @Test
    void anUnknownScopeIsNamedInTheError() {
        assertThatThrownBy(() -> keys(List.of(), sha256Hex("k") + ":admin"))
                .hasMessageContaining("'admin'").hasMessageContaining("rate-limit, alpaca-data, export");
    }
}
