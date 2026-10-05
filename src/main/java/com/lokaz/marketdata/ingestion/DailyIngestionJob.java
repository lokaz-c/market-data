package com.lokaz.marketdata.ingestion;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lokaz.marketdata.alpaca.AlpacaProperties;

/** Weekday evening job that refreshes the configured symbols. Disabled in the backfill profile and in tests. */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty("market-data.ingestion.schedule.enabled")
public class DailyIngestionJob {

    private static final Logger log = LoggerFactory.getLogger(DailyIngestionJob.class);

    private final IngestionService ingestion;
    private final IngestionProperties properties;
    private final AlpacaProperties alpaca;
    private final Clock clock;

    public DailyIngestionJob(IngestionService ingestion, IngestionProperties properties, AlpacaProperties alpaca,
            Clock clock) {
        this.ingestion = ingestion;
        this.properties = properties;
        this.alpaca = alpaca;
        this.clock = clock;
    }

    @Scheduled(cron = "${market-data.ingestion.schedule.cron}", zone = "${market-data.ingestion.schedule.zone}")
    public void run() {
        if (!alpaca.hasCredentials()) {
            log.info("Daily ingestion skipped: ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY are not set");
            return;
        }
        if (properties.symbols().isEmpty()) {
            log.info("Daily ingestion skipped: INGEST_SYMBOLS is empty");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(properties.schedule().lookbackDays());
        ingestion.ingest(new IngestionRequest(IngestionKind.DAILY, properties.symbols(), from, today));
    }
}
