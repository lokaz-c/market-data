package com.lokaz.marketdata.ingestion;

import org.jspecify.annotations.Nullable;

/** Outcome of one ingestion run, as also written to the ingestion_runs table. */
public record IngestionResult(
        long runId,
        Status status,
        int barsReceived,
        int barsUpserted,
        int barsRejected,
        int splitsUpserted,
        int httpRetries,
        @Nullable String error) {

    public enum Status {
        SUCCEEDED, FAILED
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }
}
