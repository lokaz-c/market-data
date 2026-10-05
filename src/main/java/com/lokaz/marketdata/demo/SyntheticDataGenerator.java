package com.lokaz.marketdata.demo;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.lokaz.marketdata.sql.SqlFiles;

/**
 * Generates clearly labelled synthetic bars in SQL (generate_series plus a window-function random walk), using
 * the same SQL files as scripts/explain.sh. Everything runs in one transaction so setseed() applies to every
 * random() call and the data is repeatable.
 */
@Component
public class SyntheticDataGenerator {

    private static final String SYMBOLS = SqlFiles.load("synthetic/1_symbols.sql");
    private static final String SPLITS = SqlFiles.load("synthetic/2_splits.sql");
    private static final String BARS = SqlFiles.load("synthetic/3_bars.sql");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public SyntheticDataGenerator(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public record Result(int symbols, int splits, int bars) {
    }

    public Result generate(int symbols, int years, LocalDate endDate, double seed) {
        return transactions.execute(status -> {
            jdbc.sql("SELECT setseed(:seed)").param("seed", seed).query().singleRow();
            int newSymbols = jdbc.sql(SYMBOLS).param("symbols", symbols).update();
            int splits = jdbc.sql(SPLITS).param("years", years).param("endDate", endDate).update();
            int bars = jdbc.sql(BARS).param("years", years).param("endDate", endDate).update();
            return new Result(newSymbols, splits, bars);
        });
    }

    public boolean hasSyntheticData() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM symbols WHERE source = 'synthetic')").query(Boolean.class).single();
    }
}
