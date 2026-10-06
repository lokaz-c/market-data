package com.lokaz.marketdata.demo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param seed     when to load synthetic demo data at startup (DEMO_SEED); it is never loaded twice
 * @param symbols  number of synthetic symbols (DEMO_SYMBOLS)
 * @param years    years of daily bars per symbol (DEMO_YEARS)
 * @param randomSeed PostgreSQL setseed() value, in [-1, 1], so the demo data is repeatable
 */
@Validated
@ConfigurationProperties("market-data.demo")
public record SyntheticDataProperties(@NotNull SeedMode seed, @Min(1) @Max(999) int symbols,
        @Min(1) @Max(30) int years, double randomSeed) {

    public enum SeedMode {
        /** Never generate synthetic data. */
        NEVER,
        /** Only when no Alpaca keys are configured: the local `docker compose up` default. */
        WHEN_NO_KEYS,
        /**
         * Even with keys: for a public deployment that ingests Alpaca data for keyed use but may only display
         * synthetic data publicly until Alpaca gives written consent.
         */
        ALWAYS;

        boolean shouldSeed(boolean hasAlpacaKeys, boolean hasSyntheticData) {
            if (hasSyntheticData) {
                return false;
            }
            return this == ALWAYS || (this == WHEN_NO_KEYS && !hasAlpacaKeys);
        }
    }
}
