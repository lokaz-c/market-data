SELECT ts, open, high, low, close, volume
FROM bars
WHERE symbol_id = :symbolId
  AND ts BETWEEN :fromDate AND :toDate
ORDER BY ts
