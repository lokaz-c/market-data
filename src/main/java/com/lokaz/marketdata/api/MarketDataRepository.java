package com.lokaz.marketdata.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lokaz.marketdata.api.Dtos.Bar;
import com.lokaz.marketdata.api.Dtos.IndicatorRow;
import com.lokaz.marketdata.api.Dtos.Levels;
import com.lokaz.marketdata.api.Dtos.Pivots;
import com.lokaz.marketdata.api.Dtos.Range;
import com.lokaz.marketdata.api.Dtos.SymbolSummary;
import com.lokaz.marketdata.sql.SqlFiles;

/** Read queries for the API. The SQL lives in src/main/resources/sql/api. */
@Repository
public class MarketDataRepository {

    private static final String FIND_SYMBOL = SqlFiles.load("api/find_symbol.sql");
    private static final String LIST_SYMBOLS = SqlFiles.load("api/list_symbols.sql");
    private static final String BARS_PAGE_SPLIT = SqlFiles.load("api/bars_page_split.sql");
    private static final String BARS_PAGE_RAW = SqlFiles.load("api/bars_page_raw.sql");
    private static final String INDICATORS = SqlFiles.load("api/indicators.sql");
    private static final String LEVELS = SqlFiles.load("api/levels.sql");
    private static final String LATEST_BAR_DATE = SqlFiles.load("api/latest_bar_date.sql");
    private static final String EXPORT_SPLIT = SqlFiles.load("api/export_bars.sql");
    private static final String EXPORT_RAW = SqlFiles.load("api/export_bars_raw.sql");
    private static final int EXPORT_FETCH_SIZE = 5_000;

    private final JdbcClient jdbc;

    public MarketDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SymbolRef> findSymbol(String ticker, List<String> sources) {
        return jdbc.sql(FIND_SYMBOL)
                .param("ticker", ticker)
                .param("sources", sources.toArray(String[]::new))
                .query(SymbolRef.class)
                .optional();
    }

    List<SymbolSummary> listSymbols(String prefix, List<String> sources, int limit) {
        return jdbc.sql(LIST_SYMBOLS)
                .param("prefix", prefix)
                .param("sources", sources.toArray(String[]::new))
                .param("limit", limit)
                .query((rs, n) -> new SymbolSummary(rs.getString("ticker"), rs.getString("name"),
                        rs.getString("source"), rs.getObject("first_bar", LocalDate.class),
                        rs.getObject("last_bar", LocalDate.class), Decimals.get(rs, "last_close")))
                .list();
    }

    Optional<LocalDate> latestBarDate(int symbolId) {
        return jdbc.sql(LATEST_BAR_DATE).param("symbolId", symbolId).query(LocalDate.class).optional();
    }

    List<Bar> barsPage(int symbolId, boolean splitAdjusted, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(splitAdjusted ? BARS_PAGE_SPLIT : BARS_PAGE_RAW)
                .param("symbolId", symbolId)
                .param("fromDate", from)
                .param("toDate", to)
                .param("limit", limit)
                .query((rs, n) -> bar(rs))
                .list();
    }

    List<IndicatorRow> indicatorsPage(int symbolId, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(INDICATORS)
                .param("symbolId", symbolId)
                .param("fromDate", from)
                .param("toDate", to)
                .param("limit", limit)
                .query((rs, n) -> new IndicatorRow(rs.getObject("ts", LocalDate.class), Decimals.get(rs, "close"),
                        Decimals.get(rs, "sma20"), Decimals.get(rs, "sma50"), Decimals.get(rs, "sma200"),
                        Decimals.get(rs, "volatility20"), Decimals.get(rs, "atr14"), Decimals.get(rs, "high52w"),
                        Decimals.get(rs, "low52w"), Decimals.get(rs, "pivot"), Decimals.get(rs, "r1"),
                        Decimals.get(rs, "s1")))
                .list();
    }

    Optional<Levels> levels(SymbolRef symbol, LocalDate asOf) {
        return jdbc.sql(LEVELS)
                .param("symbolId", symbol.id())
                .param("asOf", asOf)
                .query((rs, n) -> {
                    LocalDate date = rs.getObject("as_of", LocalDate.class);
                    var pivots = new Pivots(date, Decimals.get(rs, "p"), Decimals.get(rs, "r1"), Decimals.get(rs, "r2"),
                            Decimals.get(rs, "r3"), Decimals.get(rs, "s1"), Decimals.get(rs, "s2"), Decimals.get(rs, "s3"));
                    return new Levels(symbol.ticker(), symbol.source(), date, Decimals.get(rs, "close"), pivots,
                            new Range(Decimals.get(rs, "high20"), Decimals.get(rs, "low20")),
                            new Range(Decimals.get(rs, "high50"), Decimals.get(rs, "low50")),
                            new Range(Decimals.get(rs, "high52w"), Decimals.get(rs, "low52w")));
                })
                .optional();
    }

    /** Streams one symbol's bars as CSV lines; uses a cursor (fetch size) so memory stays flat. */
    void exportCsv(SymbolRef symbol, boolean splitAdjusted, LocalDate from, LocalDate to, Writer out) {
        jdbc.sql(splitAdjusted ? EXPORT_SPLIT : EXPORT_RAW)
                .withFetchSize(EXPORT_FETCH_SIZE)
                .param("symbolId", symbol.id())
                .param("fromDate", from)
                .param("toDate", to)
                .query(rs -> {
                    Bar bar = bar(rs);
                    try {
                        out.write(symbol.ticker() + "," + bar.date() + "," + bar.open().toPlainString() + ","
                                + bar.high().toPlainString() + "," + bar.low().toPlainString() + ","
                                + bar.close().toPlainString() + "," + bar.volume() + "\n");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private static Bar bar(ResultSet rs) throws SQLException {
        return new Bar(rs.getObject("ts", LocalDate.class), Decimals.get(rs, "open"), Decimals.get(rs, "high"),
                Decimals.get(rs, "low"), Decimals.get(rs, "close"), rs.getLong("volume"));
    }
}
