-- Closes a run and, if it succeeded, records it on every symbol it covered (symbols.last_ingested_at). One
-- statement, so a run is never marked succeeded without its symbols being updated, or the other way round.
-- A failed run leaves the symbols alone: some of its pages may be written, but not all of them.
-- The last condition keeps last_ingested_at moving forward when two runs overlap.
WITH run AS (
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
    RETURNING id, status, finished_at, symbols
)
UPDATE symbols s
SET last_ingested_at      = run.finished_at,
    last_ingestion_run_id = run.id
FROM run
WHERE run.status = 'succeeded'
  AND s.ticker = ANY(run.symbols)
  AND (s.last_ingested_at IS NULL OR s.last_ingested_at < run.finished_at)
