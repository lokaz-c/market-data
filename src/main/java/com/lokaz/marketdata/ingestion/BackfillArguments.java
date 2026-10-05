package com.lokaz.marketdata.ingestion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code --from=YYYY-MM-DD --to=YYYY-MM-DD --symbols=AAPL,MSFT}. {@code --to} defaults to today and
 * {@code --symbols} to the configured INGEST_SYMBOLS.
 */
record BackfillArguments(LocalDate from, LocalDate to, List<String> symbols) {

    static BackfillArguments parse(ApplicationArguments args, LocalDate today, List<String> defaultSymbols) {
        LocalDate from = date(single(args, "from"), "from");
        if (from == null) {
            throw new IllegalArgumentException("--from=YYYY-MM-DD is required");
        }
        LocalDate to = date(single(args, "to"), "to");
        String symbolsArg = single(args, "symbols");
        List<String> symbols = symbolsArg == null ? defaultSymbols : Arrays.asList(symbolsArg.split(","));
        return new BackfillArguments(from, to == null ? today : to, Tickers.normalizeAll(symbols));
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("--" + name + " given more than once");
        }
        return values.getFirst();
    }

    private static LocalDate date(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--" + name + " must be YYYY-MM-DD, got '" + value + "'");
        }
    }
}
