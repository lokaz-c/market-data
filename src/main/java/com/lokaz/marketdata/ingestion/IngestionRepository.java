package com.lokaz.marketdata.ingestion;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SimplePropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lokaz.marketdata.sql.SqlFiles;

/**
 * Writes for ingestion: symbols, bars, splits and the run log. Single statements use JdbcClient; the bar
 * and split upserts use NamedParameterJdbcTemplate because JdbcClient has no batch API.
 */
@Repository
public class IngestionRepository {

    private static final String ENSURE_SYMBOLS = SqlFiles.load("ingestion/ensure_symbols.sql");
    private static final String UPSERT_BAR = SqlFiles.load("ingestion/upsert_bar.sql");
    private static final String UPSERT_SPLIT = SqlFiles.load("ingestion/upsert_split.sql");
    private static final String START_RUN = SqlFiles.load("ingestion/start_run.sql");
    private static final String FINISH_RUN = SqlFiles.load("ingestion/finish_run.sql");
    private static final String ABANDON_STALE_RUNS = SqlFiles.load("ingestion/abandon_stale_runs.sql");

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchJdbc;

    public IngestionRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchJdbc) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
    }

    /** Returns ticker -> symbol id, inserting tickers that are not in the symbols table yet. */
    public Map<String, Integer> ensureSymbols(Collection<String> tickers) {
        var ids = new HashMap<String, Integer>();
        jdbc.sql(ENSURE_SYMBOLS)
                .param("tickers", tickers.toArray(String[]::new))
                .query((rs, n) -> Map.entry(rs.getString("ticker"), rs.getInt("id")))
                .list()
                .forEach(e -> ids.put(e.getKey(), e.getValue()));
        return ids;
    }

    /** @return rows inserted or changed (unchanged rows count 0) */
    public int upsertBars(List<BarRow> rows) {
        return batch(UPSERT_BAR, rows);
    }

    /** @return rows inserted or changed */
    public int upsertSplits(List<SplitRow> rows) {
        return batch(UPSERT_SPLIT, rows);
    }

    public long startRun(IngestionRequest request) {
        return jdbc.sql(START_RUN)
                .param("kind", request.kind().dbValue())
                .param("rangeStart", request.from())
                .param("rangeEnd", request.to())
                .param("symbols", request.symbols().toArray(String[]::new))
                .query(Long.class)
                .single();
    }

    public void finishRun(long runId, IngestionResult.Status status, RunCounters counters, @Nullable String error) {
        jdbc.sql(FINISH_RUN)
                .param("id", runId)
                .param("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .param("barsReceived", counters.barsReceived)
                .param("barsUpserted", counters.barsUpserted)
                .param("barsRejected", counters.barsRejected)
                .param("splitsUpserted", counters.splitsUpserted)
                .param("httpRetries", counters.httpRetries)
                .param("error", error)
                .update();
    }

    public int abandonStaleRuns(Duration staleAfter) {
        return jdbc.sql(ABANDON_STALE_RUNS)
                .param("staleAfter", staleAfter.toSeconds() + " seconds")
                .update();
    }

    private int batch(String sql, List<?> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] params = rows.stream()
                .map(SimplePropertySqlParameterSource::new)
                .toArray(SqlParameterSource[]::new);
        int written = 0;
        for (int count : batchJdbc.batchUpdate(sql, params)) {
            // PgJDBC reports exact counts without reWriteBatchedInserts: 1 = inserted/changed, 0 = unchanged.
            written += Math.max(count, 0);
        }
        return written;
    }

    /** Mutable tallies for one run; only touched by the thread running it. */
    public static final class RunCounters {
        int barsReceived;
        int barsUpserted;
        int barsRejected;
        int splitsUpserted;
        int httpRetries;
    }
}
