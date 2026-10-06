-- One keyset page of split-adjusted bars. :fromDate is the page's lower bound: the requested start date on the
-- first page, the day after the previous page's last bar afterwards. That keeps every page a single
-- range scan on the primary key, however deep the page; OFFSET would read and discard all earlier rows.
SELECT ts, open, high, low, close, volume, feed
FROM bars_split_adjusted
WHERE symbol_id = :symbolId
  AND ts BETWEEN :fromDate AND :toDate
ORDER BY ts
LIMIT :limit
