UPDATE ingestion_runs
SET status          = :status,
    finished_at     = now(),
    bars_received   = :barsReceived,
    bars_upserted   = :barsUpserted,
    bars_rejected   = :barsRejected,
    splits_upserted = :splitsUpserted,
    http_retries    = :httpRetries,
    error           = :error
WHERE id = :id
