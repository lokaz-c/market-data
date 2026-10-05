package com.lokaz.marketdata.ingestion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** What to ingest: raw daily bars for {@code symbols} from {@code from} to {@code to}, both inclusive. */
public record IngestionRequest(IngestionKind kind, List<String> symbols, LocalDate from, LocalDate to) {

    public IngestionRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        symbols = Tickers.normalizeAll(Objects.requireNonNull(symbols, "symbols"));
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("At least one symbol is required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from (" + from + ") is after to (" + to + ")");
        }
    }
}
