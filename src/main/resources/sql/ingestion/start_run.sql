INSERT INTO ingestion_runs (kind, range_start, range_end, symbols)
VALUES (:kind, :rangeStart, :rangeEnd, CAST(:symbols AS text[]))
RETURNING id
