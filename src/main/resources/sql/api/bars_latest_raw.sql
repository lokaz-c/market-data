-- Same as bars_latest_split.sql, on unadjusted prices.
SELECT ts, open, high, low, close, volume, feed
FROM (
    SELECT ts, open, high, low, close, volume, feed
    FROM bars
    WHERE symbol_id = :symbolId
      AND ts <= :toDate
    ORDER BY ts DESC
    LIMIT :limit
) latest
ORDER BY ts
