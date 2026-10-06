package com.lokaz.marketdata.ingestion;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Ticker format shared by ingestion and the API; matches the symbols_ticker_format CHECK constraint. */
public final class Tickers {

    /** Letters first, then letters, digits or a dot (BRK.B), at most 10 characters. */
    public static final String REGEX = "^[A-Z][A-Z0-9.]{0,9}$";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private Tickers() {
    }

    public static boolean isValid(String ticker) {
        return ticker != null && PATTERN.matcher(ticker).matches();
    }

    /** Trims and upper-cases; throws if the result is not a valid ticker. */
    public static String normalize(String raw) {
        String ticker = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!isValid(ticker)) {
            throw new IllegalArgumentException("Invalid ticker: '" + raw + "'");
        }
        return ticker;
    }

    /** Normalizes and de-duplicates, keeping the first-seen order. */
    public static List<String> normalizeAll(Collection<String> raw) {
        var unique = new LinkedHashSet<String>();
        for (String ticker : raw) {
            if (ticker != null && !ticker.isBlank()) {
                unique.add(normalize(ticker));
            }
        }
        return List.copyOf(unique);
    }
}
