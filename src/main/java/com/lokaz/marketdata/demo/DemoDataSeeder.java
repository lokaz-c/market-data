package com.lokaz.marketdata.demo;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.lokaz.marketdata.alpaca.AlpacaProperties;

/** Seeds synthetic demo data at startup so `docker compose up` shows charts before any Alpaca keys exist. */
@Component
@Profile("!backfill")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final SyntheticDataGenerator generator;
    private final SyntheticDataProperties properties;
    private final AlpacaProperties alpaca;
    private final Clock clock;

    public DemoDataSeeder(SyntheticDataGenerator generator, SyntheticDataProperties properties,
            AlpacaProperties alpaca, Clock clock) {
        this.generator = generator;
        this.properties = properties;
        this.alpaca = alpaca;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.seedWhenNoKeys() || alpaca.hasCredentials() || generator.hasSyntheticData()) {
            return;
        }
        // End on the last weekday before today so the data looks current without claiming today's session.
        LocalDate end = LocalDate.now(clock).minusDays(1);
        log.info("No Alpaca keys: generating SYNTHETIC demo data ({} symbols x {} years)", properties.symbols(),
                properties.years());
        var result = generator.generate(properties.symbols(), properties.years(), end, properties.seed());
        log.info("Synthetic demo data ready: {}", result);
    }
}
