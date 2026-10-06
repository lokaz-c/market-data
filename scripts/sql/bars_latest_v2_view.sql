-- api/bars_latest_split.sql against bars_split_adjusted_v2, the view as V2 defined it (scripts/explain.py creates
-- it), kept only for comparison. V2 computed the split factors in a WITH clause. PostgreSQL never flattens a
-- subquery that has a WITH list, so the view ran as a separate "Subquery Scan": ORDER BY ts DESC LIMIT could not
-- reach the bars primary key, and the query read every bar of the symbol to keep the latest :limit.
SELECT ts, open, high, low, close, volume
FROM (
    SELECT ts, open, high, low, close, volume
    FROM bars_split_adjusted_v2
    WHERE symbol_id = :symbolId
      AND ts <= :toDate
    ORDER BY ts DESC
    LIMIT :limit
) latest
ORDER BY ts
