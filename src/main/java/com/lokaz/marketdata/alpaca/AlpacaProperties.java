package com.lokaz.marketdata.alpaca;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for Alpaca's Market Data API. Keys come from the environment (ALPACA_API_KEY_ID,
 * ALPACA_API_SECRET_KEY); without them the service runs on synthetic demo data only.
 */
@Validated
@ConfigurationProperties("market-data.alpaca")
public record AlpacaProperties(
        @NotNull URI baseUrl,
        String keyId,
        String secretKey,
        // Paper-only (free) accounts are entitled to IEX data only, so iex is the default.
        @Pattern(regexp = "iex|sip") String feed,
        // Alpaca allows up to 10,000 data points per page, counted across all symbols in the request.
        @Min(1) @Max(10_000) int pageLimit,
        @Min(1) @Max(1_000) int symbolsPerRequest,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull Retry retry) {

    public boolean hasCredentials() {
        return StringUtils.hasText(keyId) && StringUtils.hasText(secretKey);
    }

    /**
     * @param maxAttempts  total attempts per HTTP call, including the first
     * @param initialDelay backoff ceiling for the first retry; doubles on each further retry
     * @param maxDelay     cap on any single wait, including waits requested by the server
     */
    public record Retry(@Min(1) int maxAttempts, @NotNull Duration initialDelay, @NotNull Duration maxDelay) {
    }
}
