package com.lokaz.marketdata.demo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param seedWhenNoKeys load synthetic demo data at startup when no Alpaca keys are configured and no
 *                       synthetic data exists yet (DEMO_SEED)
 * @param symbols        number of synthetic symbols (DEMO_SYMBOLS)
 * @param years          years of daily bars per symbol (DEMO_YEARS)
 * @param seed           PostgreSQL setseed() value, in [-1, 1], so the demo data is repeatable
 */
@Validated
@ConfigurationProperties("market-data.demo")
public record SyntheticDataProperties(boolean seedWhenNoKeys, @Min(1) @Max(999) int symbols,
        @Min(1) @Max(30) int years, double seed) {
}
