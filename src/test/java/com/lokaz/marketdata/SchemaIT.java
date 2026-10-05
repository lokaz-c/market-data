package com.lokaz.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

/** The CHECK constraints are the last line of defence; these tests make sure they exist and bite. */
class SchemaIT extends IntegrationTest {

    private int symbolId;

    @BeforeEach
    void symbol() {
        symbolId = jdbc.sql("INSERT INTO symbols (ticker) VALUES ('AAPL') RETURNING id").query(Integer.class).single();
    }

    @Test
    void migrationsApplied() {
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Integer.class).single())
                .isGreaterThanOrEqualTo(2);
    }

    @ParameterizedTest(name = "{5}")
    @CsvSource({
            "100, 90, 110, 100, 1, bars_high_gte_low",
            "120, 110, 90, 100, 1, bars_open_close_in_range",
            "100, 110, 90, 100, -1, bars_volume_nonnegative",
            "0, 0, 0, 0, 1, bars_prices_positive",
    })
    void barsRejectImpossibleValues(String o, String h, String l, String c, long v, String constraint) {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO bars (symbol_id, ts, open, high, low, close, volume)
                VALUES (:id, DATE '2024-01-02', CAST(:o AS numeric), CAST(:h AS numeric), CAST(:l AS numeric),
                        CAST(:c AS numeric), :v)""")
                .param("id", symbolId).param("o", o).param("h", h).param("l", l).param("c", c).param("v", v).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void tickersMustBeUppercaseSymbols() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO symbols (ticker) VALUES ('aapl; drop')").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("symbols_ticker_format");
    }

    @Test
    void aSplitMustChangeTheShareCount() {
        assertThatThrownBy(() -> jdbc.sql(
                "INSERT INTO splits (symbol_id, ex_date, old_rate, new_rate, source) VALUES (:id, DATE '2024-01-02', 1, 1, 'alpaca')")
                .param("id", symbolId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("splits_not_identity");
    }

    @Test
    void aFinishedRunMustHaveAFinishTime() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO ingestion_runs (kind, status, range_start, range_end, symbols)
                VALUES ('daily', 'succeeded', DATE '2024-01-02', DATE '2024-01-02', ARRAY['AAPL'])""").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ingestion_runs_finish_valid");
    }
}
