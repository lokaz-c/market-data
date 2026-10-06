package com.lokaz.marketdata.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lokaz.marketdata.IntegrationTest;

class SyntheticDataIT extends IntegrationTest {

    private static final LocalDate END = LocalDate.of(2026, 10, 2);

    @Autowired
    SyntheticDataGenerator generator;

    private static int weekdaysBetween(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    @Test
    void generatesLabelledSymbolsAndAWeekdayBarForEachDay() {
        var result = generator.generate(30, 2, END, 0.42);

        assertThat(result.symbols()).isEqualTo(30);
        assertThat(result.bars()).isEqualTo(30 * weekdaysBetween(END.minusYears(2), END));
        assertThat(jdbc.sql("SELECT count(*) FROM symbols WHERE source <> 'synthetic' OR ticker !~ '^S[0-9]{3}$'")
                .query(Integer.class).single()).isZero();
        assertThat(result.splits()).isPositive();
    }

    @Test
    void theSplitAdjustedSeriesHasNoJumpAtSyntheticSplits() {
        generator.generate(30, 2, END, 0.42);

        // Largest close-to-close move across each split's ex-date, raw vs adjusted.
        Map<String, Object> moves = jdbc.sql("""
                WITH steps AS (
                    SELECT sp.symbol_id, sp.ex_date,
                           (SELECT close FROM bars b WHERE b.symbol_id = sp.symbol_id AND b.ts < sp.ex_date ORDER BY ts DESC LIMIT 1) AS raw_before,
                           (SELECT close FROM bars b WHERE b.symbol_id = sp.symbol_id AND b.ts >= sp.ex_date ORDER BY ts LIMIT 1) AS raw_after,
                           (SELECT close FROM bars_split_adjusted a WHERE a.symbol_id = sp.symbol_id AND a.ts < sp.ex_date ORDER BY ts DESC LIMIT 1) AS adj_before,
                           (SELECT close FROM bars_split_adjusted a WHERE a.symbol_id = sp.symbol_id AND a.ts >= sp.ex_date ORDER BY ts LIMIT 1) AS adj_after
                    FROM splits sp
                )
                SELECT min(abs(ln(raw_after / raw_before))) AS smallest_raw_jump,
                       max(abs(ln(adj_after / adj_before))) AS largest_adjusted_move
                FROM steps""").query().singleRow();

        // Splits are at least 2-for-1 (|ln| >= 0.69); daily synthetic moves are a few percent.
        assertThat(((Number) moves.get("smallest_raw_jump")).doubleValue()).isGreaterThan(0.5);
        assertThat(((Number) moves.get("largest_adjusted_move")).doubleValue()).isLessThan(0.2);
    }

    @Test
    void theSameSeedGivesTheSameData() {
        generator.generate(5, 1, END, 0.42);
        String first = checksum();
        jdbc.sql("TRUNCATE bars, splits, ingestion_runs, symbols RESTART IDENTITY CASCADE").update();
        generator.generate(5, 1, END, 0.42);
        assertThat(checksum()).isEqualTo(first);
    }

    @Test
    void runningAgainDoesNotDuplicateOrAlterExistingData() {
        generator.generate(5, 1, END, 0.42);
        String before = checksum();
        var second = generator.generate(5, 1, END, 0.42);
        assertThat(second.bars()).isZero();
        assertThat(second.splits()).isZero();
        assertThat(checksum()).isEqualTo(before);
    }

    private String checksum() {
        return jdbc.sql("SELECT md5(string_agg(symbol_id || ts::text || close::text || volume::text, ',' ORDER BY symbol_id, ts)) FROM bars")
                .query(String.class).single();
    }
}
