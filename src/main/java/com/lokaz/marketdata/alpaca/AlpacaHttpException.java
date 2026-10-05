package com.lokaz.marketdata.alpaca;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** A non-2xx response from Alpaca. */
public class AlpacaHttpException extends RuntimeException {

    private final int status;
    private final @Nullable Instant rateLimitReset;

    public AlpacaHttpException(int status, String body, @Nullable Instant rateLimitReset) {
        super("Alpaca returned HTTP " + status + (body.isBlank() ? "" : ": " + body));
        this.status = status;
        this.rateLimitReset = rateLimitReset;
    }

    public int status() {
        return status;
    }

    /** When the rate-limit quota resets (X-RateLimit-Reset), if Alpaca sent it. */
    public @Nullable Instant rateLimitReset() {
        return rateLimitReset;
    }

    /** 429 and 5xx are worth retrying; other 4xx (bad request, bad keys, forbidden feed) are not. */
    public boolean isRetryable() {
        return status == 429 || status >= 500;
    }
}
