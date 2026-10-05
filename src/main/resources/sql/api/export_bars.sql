-- Bulk export for one symbol (the export streams symbols one at a time, each an ordered PK range scan).
SELECT ts, open, high, low, close, volume
FROM bars_split_adjusted
WHERE symbol_id = :symbolId
  AND ts BETWEEN :fromDate AND :toDate
ORDER BY ts
