-- The :limit latest split-adjusted bars on or before :toDate (GET /v1/bars/{ticker}?last=N), oldest first.
-- The inner query reads the primary key (symbol_id, ts) backwards from :toDate and stops after :limit rows, so
-- its cost depends on N, not on how much history the symbol has (see docs/explain.md). The outer ORDER BY
-- re-sorts those N rows into the same order as every other bars response.
SELECT ts, open, high, low, close, volume, feed
FROM (
    SELECT ts, open, high, low, close, volume, feed
    FROM bars_split_adjusted
    WHERE symbol_id = :symbolId
      AND ts <= :toDate
    ORDER BY ts DESC
    LIMIT :limit
) latest
ORDER BY ts
