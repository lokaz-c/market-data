package com.lokaz.marketdata.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param keySha256           SHA-256 hex digests of the API keys allowed to use bulk export and to read
 *                            sources that are not public (API_KEY_SHA256, comma-separated). Only digests are
 *                            configured, so the keys themselves never sit in config or env.
 * @param publicSources       symbol sources served without a key (PUBLIC_DATA_SOURCES). Defaults to synthetic
 *                            only: Alpaca's terms forbid publicly displaying its data without written consent.
 * @param maxExportSymbols    symbols per export request
 * @param corsAllowedOrigins  origins allowed to call /v1 from a browser (CORS_ALLOWED_ORIGINS), for hosting
 *                            the chart explorer on a separate static host
 */
@Validated
@ConfigurationProperties("market-data.api")
public record ApiProperties(
        @NotNull List<String> keySha256,
        @NotNull List<String> publicSources,
        @Min(1) @Max(500) int maxExportSymbols,
        @NotNull List<String> corsAllowedOrigins,
        @Valid @NotNull RateLimit rateLimit) {

    /**
     * Token bucket per client IP: up to {@code capacity} requests at once, refilled at
     * {@code requestsPerMinute}.
     */
    public record RateLimit(boolean enabled, @Min(1) int capacity, @Min(1) int requestsPerMinute) {
    }
}
