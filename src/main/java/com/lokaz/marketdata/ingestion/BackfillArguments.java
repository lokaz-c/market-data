package com.lokaz.marketdata.ingestion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code --from=YYYY-MM-DD --to=YYYY-MM-DD --symbols=AAPL,MSFT --kind=backfill|daily}. {@code --to}
 * defaults to today, {@code --symbols} to the configured INGEST_SYMBOLS, and {@code --kind} (the label in
 * ingestion_runs) to backfill; the scheduled GitHub Actions ingestion passes --kind=daily.
 */
record BackfillArguments(LocalDate from, LocalDate to, List<String> symbols, IngestionKind kind) {

    static BackfillArguments parse(ApplicationArguments args, LocalDate today, List<String> defaultSymbols) {
        LocalDate from = date(single(args, "from"), "from");
        if (from == null) {
            throw new IllegalArgumentException("--from=YYYY-MM-DD is required");
        }
        LocalDate to = date(single(args, "to"), "to");
        String symbolsArg = single(args, "symbols");
        List<String> symbols = symbolsArg == null ? defaultSymbols : Arrays.asList(symbolsArg.split(","));
        String kindArg = single(args, "kind");
        IngestionKind kind;
        try {
            kind = kindArg == null ? IngestionKind.BACKFILL : IngestionKind.valueOf(kindArg.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--kind must be backfill or daily, got '" + kindArg + "'");
        }
        return new BackfillArguments(from, to == null ? today : to, Tickers.normalizeAll(symbols), kind);
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
