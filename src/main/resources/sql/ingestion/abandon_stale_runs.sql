-- A run still marked 'running' long after it started belongs to a process that died mid-run.
UPDATE ingestion_runs
SET status = 'failed', finished_at = now(), error = 'abandoned: the process stopped before the run finished'
WHERE status = 'running' AND started_at < now() - CAST(:staleAfter AS interval)
