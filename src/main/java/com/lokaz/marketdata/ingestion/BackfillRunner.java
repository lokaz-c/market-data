package com.lokaz.marketdata.ingestion;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.lokaz.marketdata.alpaca.AlpacaProperties;

/**
 * One-off backfill, run as {@code java -jar app.jar --spring.profiles.active=backfill --from=2016-01-01
 * --to=2026-10-02 --symbols=AAPL,MSFT}. The profile turns off the web server and the scheduler, so the JVM
 * exits when the run ends; a failed run throws, which makes the process exit non-zero.
 */
@Component
@Profile("backfill")
public class BackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

    private final IngestionService ingestion;
    private final IngestionProperties properties;
    private final AlpacaProperties alpaca;
    private final Clock clock;

    public BackfillRunner(IngestionService ingestion, IngestionProperties properties, AlpacaProperties alpaca,
            Clock clock) {
        this.ingestion = ingestion;
        this.properties = properties;
        this.alpaca = alpaca;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!alpaca.hasCredentials()) {
            throw new IllegalStateException("Backfill needs ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY");
        }
        var parsed = BackfillArguments.parse(args, LocalDate.now(clock), properties.symbols());
        IngestionResult result = ingestion.ingest(
                new IngestionRequest(parsed.kind(), parsed.symbols(), parsed.from(), parsed.to()));
        log.info("Backfill finished: {}", result);
        if (!result.succeeded()) {
            throw new IllegalStateException("Backfill run " + result.runId() + " failed: " + result.error());
        }
    }
}
