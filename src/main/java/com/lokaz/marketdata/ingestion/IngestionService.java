package com.lokaz.marketdata.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.lokaz.marketdata.alpaca.Adjustment;
import com.lokaz.marketdata.alpaca.AlpacaBar;
import com.lokaz.marketdata.alpaca.AlpacaClient;
import com.lokaz.marketdata.alpaca.AlpacaSplit;
import com.lokaz.marketdata.ingestion.IngestionRepository.RunCounters;
import com.lokaz.marketdata.ingestion.IngestionResult.Status;

/**
 * Pulls raw daily bars and splits from Alpaca and upserts them. Every run is logged in ingestion_runs.
 *
 * <p>Re-running any range is safe: bars and splits are upserted on their natural keys, so a retry after a
 * failure, an overlapping daily window or two runs at once all converge on the same rows.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_ERROR_CHARS = 2_000;
    /** Corporate actions are filtered by process date; look a little earlier so no split near `from` is missed. */
    private static final int SPLIT_LOOKBACK_DAYS = 30;
    private static final Duration STALE_RUN_AFTER = Duration.ofHours(6);

    private final AlpacaClient alpaca;
    private final IngestionRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public IngestionService(AlpacaClient alpaca, IngestionRepository repository, TransactionTemplate transactions,
            Clock clock) {
        this.alpaca = alpaca;
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    public IngestionResult ingest(IngestionRequest request) {
        repository.abandonStaleRuns(STALE_RUN_AFTER);
        long runId = repository.startRun(request);
        var counters = new RunCounters();
        log.info("Ingestion run {} started: {} {} symbols {}..{}", runId, request.kind(), request.symbols().size(),
                request.from(), request.to());
        try {
            Map<String, Integer> symbolIds = repository.ensureSymbols(request.symbols());
            if (symbolIds.size() != request.symbols().size()) {
                throw new IllegalStateException("Could not resolve ids for all symbols: " + request.symbols());
            }
            ingestSplits(request, symbolIds, counters);
            ingestBars(request, symbolIds, counters);
            repository.finishRun(runId, Status.SUCCEEDED, counters, null);
            log.info("Ingestion run {} succeeded: received={} upserted={} rejected={} splits={} retries={}", runId,
                    counters.barsReceived, counters.barsUpserted, counters.barsRejected, counters.splitsUpserted,
                    counters.httpRetries);
            return result(runId, Status.SUCCEEDED, counters, null);
        } catch (RuntimeException e) {
            String error = truncate(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Ingestion run {} failed", runId, e);
            repository.finishRun(runId, Status.FAILED, counters, error);
            return result(runId, Status.FAILED, counters, error);
        }
    }

    /**
     * Splits are fetched from {@code from} up to today, not just to {@code to}: a bar's split-adjusted price
     * depends on every split after it, including splits after the requested range.
     */
    private void ingestSplits(IngestionRequest request, Map<String, Integer> symbolIds, RunCounters counters) {
        LocalDate today = LocalDate.now(clock);
        List<AlpacaSplit> splits = alpaca.fetchSplits(request.symbols(), request.from().minusDays(SPLIT_LOOKBACK_DAYS),
                today, (n, wait, cause) -> counters.httpRetries++);
        var rows = new ArrayList<SplitRow>();
        for (AlpacaSplit split : splits) {
            Integer symbolId = symbolIds.get(split.symbol());
            // Ignore announced splits that have not taken effect: adjusting history for them would be wrong today.
            if (symbolId != null && split.exDate() != null && !split.exDate().isAfter(today)) {
                rows.add(new SplitRow(symbolId, split.exDate(), split.oldRate(), split.newRate()));
            }
        }
        counters.splitsUpserted += transactions.execute(status -> repository.upsertSplits(rows));
    }

    private void ingestBars(IngestionRequest request, Map<String, Integer> symbolIds, RunCounters counters) {
        alpaca.forEachDailyBarsPage(request.symbols(), request.from(), request.to(), Adjustment.RAW,
                (n, wait, cause) -> counters.httpRetries++,
                page -> {
                    List<BarRow> rows = toValidRows(page, symbolIds, counters);
                    // One transaction per page: a failure later in a backfill keeps the pages already written.
                    counters.barsUpserted += transactions.execute(status -> repository.upsertBars(rows));
                });
    }

    private static List<BarRow> toValidRows(Map<String, List<AlpacaBar>> page, Map<String, Integer> symbolIds,
            RunCounters counters) {
        var rows = new ArrayList<BarRow>();
        page.forEach((symbol, bars) -> {
            Integer symbolId = symbolIds.get(symbol);
            for (AlpacaBar bar : bars) {
                counters.barsReceived++;
                if (symbolId == null) {
                    counters.barsRejected++;
                    log.warn("Skipping bar for unrequested symbol {}", symbol);
                    continue;
                }
                var row = new BarRow(symbolId, bar.sessionDate(), bar.open(), bar.high(), bar.low(), bar.close(),
                        bar.volume(), bar.tradeCount(), bar.vwap());
                var violation = row.violation();
                if (violation.isPresent()) {
                    counters.barsRejected++;
                    log.warn("Skipping invalid bar {} {}: {}", symbol, row.ts(), violation.get());
                    continue;
                }
                rows.add(row);
            }
        });
        return rows;
    }

    private static IngestionResult result(long runId, Status status, RunCounters c, String error) {
        return new IngestionResult(runId, status, c.barsReceived, c.barsUpserted, c.barsRejected, c.splitsUpserted,
                c.httpRetries, error);
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_CHARS ? message : message.substring(0, MAX_ERROR_CHARS);
    }
}
