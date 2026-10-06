package com.lokaz.marketdata.api;

import org.jspecify.annotations.Nullable;

record SymbolRef(int id, String ticker, @Nullable String name, String source) {
}
