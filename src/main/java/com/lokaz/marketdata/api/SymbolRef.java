package com.lokaz.marketdata.api;

import java.time.OffsetDateTime;

import org.jspecify.annotations.Nullable;

/** @param dataChangedAt changes whenever the symbol's bars or splits change (see V3__freshness_and_feed.sql) */
record SymbolRef(int id, String ticker, @Nullable String name, String source, OffsetDateTime dataChangedAt) {
}
