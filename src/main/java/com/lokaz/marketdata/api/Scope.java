package com.lokaz.marketdata.api;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** What an API key may do. Each capability a key used to unlock all at once is now a separate scope. */
public enum Scope {

    /** Not subject to the per-IP rate limit. */
    RATE_LIMIT("rate-limit"),
    /** Can read every data source, including Alpaca's, which is not public without Alpaca's written consent. */
    ALPACA_DATA("alpaca-data"),
    /** Can use the bulk export under /v1/export. */
    EXPORT("export");

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Parses a comma-separated list such as {@code rate-limit,alpaca-data}; unknown names are an error. */
    static Set<Scope> parseAll(String list) {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (String name : list.split(",")) {
            String trimmed = name.strip().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                scopes.add(Arrays.stream(values()).filter(s -> s.value.equals(trimmed)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown API key scope '" + trimmed
                                + "'; expected " + names())));
            }
        }
        return scopes;
    }

    static String names() {
        return Arrays.stream(values()).map(Scope::value).collect(Collectors.joining(", "));
    }
}
