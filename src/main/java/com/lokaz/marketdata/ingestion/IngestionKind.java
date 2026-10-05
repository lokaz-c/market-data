package com.lokaz.marketdata.ingestion;

import java.util.Locale;

public enum IngestionKind {
    DAILY, BACKFILL;

    String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
