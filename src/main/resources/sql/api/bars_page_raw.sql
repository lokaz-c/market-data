-- Same as bars_page_split.sql, on unadjusted prices.
SELECT ts, open, high, low, close, volume, feed
FROM bars
WHERE symbol_id = :symbolId
  AND ts BETWEEN :fromDate AND :toDate
ORDER BY ts
LIMIT :limit
