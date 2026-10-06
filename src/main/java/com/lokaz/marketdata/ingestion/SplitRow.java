package com.lokaz.marketdata.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SplitRow(int symbolId, LocalDate exDate, BigDecimal oldRate, BigDecimal newRate) {
}
