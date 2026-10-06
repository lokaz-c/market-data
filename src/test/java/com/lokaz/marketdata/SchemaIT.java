package com.lokaz.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/** The CHECK constraints are the last line of defence and the triggers keep data versions honest; these tests make sure they bite. */
class SchemaIT extends IntegrationTest {

    @Autowired
    TransactionTemplate transactions;

    private int symbolId;

    @BeforeEach
    void symbol() {
        symbolId = jdbc.sql("INSERT INTO symbols (ticker) VALUES ('AAPL') RETURNING id").query(Integer.class).single();
    }

    @Test
    void migrationsApplied() {
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Integer.class).single())
                .isGreaterThanOrEqualTo(3);
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
    void aBarsFeedMustBeAnAlpacaFeed() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO bars (symbol_id, ts, open, high, low, close, volume, feed)
                VALUES (:id, DATE '2024-01-02', 1, 1, 1, 1, 1, 'nasdaq')""")
                .param("id", symbolId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bars_feed_valid");
    }

    @Test
    void writingBarsMovesTheSymbolsDataVersionOncePerTransaction() {
        String before = dataVersion();
        // ctid is the row version's physical address: a second UPDATE of the symbol would move it.
        List<String> rowVersions = transactions.execute(status -> {
            var versions = new java.util.ArrayList<String>();
            for (String day : new String[] {"2024-01-02", "2024-01-03", "2024-01-04"}) {
                jdbc.sql("INSERT INTO bars (symbol_id, ts, open, high, low, close, volume) "
                        + "VALUES (:id, CAST(:day AS date), 1, 1, 1, 1, 1)").param("id", symbolId).param("day", day).update();
                versions.add(jdbc.sql("SELECT ctid::text || (data_changed_at = now()) FROM symbols WHERE id = :id")
                        .param("id", symbolId).query(String.class).single());
            }
            return versions;
        });
        assertThat(rowVersions).hasSize(3).allSatisfy(v -> assertThat(v).endsWith("true"));
        assertThat(rowVersions).as("one new row version for three statements").containsOnly(rowVersions.getFirst());
        assertThat(dataVersion()).isNotEqualTo(before);

        String afterInsert = dataVersion();
        jdbc.sql("UPDATE bars SET volume = volume WHERE false").update();
        assertThat(dataVersion()).as("a statement that changes no rows").isEqualTo(afterInsert);
        jdbc.sql("DELETE FROM bars WHERE ts = DATE '2024-01-04'").update();
        assertThat(dataVersion()).as("a delete").isNotEqualTo(afterInsert);
    }

    private String dataVersion() {
        return jdbc.sql("SELECT data_changed_at::text FROM symbols WHERE id = :id").param("id", symbolId)
                .query(String.class).single();
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
