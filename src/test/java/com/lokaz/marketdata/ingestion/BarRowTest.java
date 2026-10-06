package com.lokaz.marketdata.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BarRowTest {

    private static BarRow bar(String open, String high, String low, String close, long volume) {
        return new BarRow(1, LocalDate.of(2024, 1, 3), new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), volume, 10L, new BigDecimal(low), "iex");
    }

    @ParameterizedTest(name = "{0}/{1}/{2}/{3} vol {4} -> {5}")
    @CsvSource(nullValues = "valid", value = {
            "100, 110, 90, 105, 1000, valid",
            "100, 100, 100, 100, 0, valid",
            "100, 90, 110, 100, 1000, high < low",
            "120, 110, 90, 105, 1000, 'open or close outside [low, high]'",
            "100, 110, 90, 85, 1000, 'open or close outside [low, high]'",
            "0, 110, 90, 105, 1000, non-positive price",
            "100, 110, 90, 105, -1, negative volume",
    })
    void checksTheSameInvariantsAsTheDatabase(String o, String h, String l, String c, long v, String expected) {
        assertThat(bar(o, h, l, c, v).violation().orElse(null)).isEqualTo(expected);
    }
}
