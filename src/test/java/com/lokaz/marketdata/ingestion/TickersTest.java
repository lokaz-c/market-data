package com.lokaz.marketdata.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TickersTest {

    @Test
    void normalizesCaseAndWhitespaceAndDeduplicates() {
        assertThat(Tickers.normalizeAll(List.of(" aapl", "MSFT", "brk.b", "AAPL", "")))
                .containsExactly("AAPL", "MSFT", "BRK.B");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1ABC", "AA-PL", "TOOLONGTICKER", "AA PL", "$SPY", "AAPL;DROP"})
    void rejectsMalformedTickers(String raw) {
        assertThatThrownBy(() -> Tickers.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
