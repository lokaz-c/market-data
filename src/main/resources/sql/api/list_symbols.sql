-- One keyset page of symbols that have data, ordered by ticker. :after is the previous page's last ticker ('' on
-- the first page), so a page is a range scan of symbols_ticker_key however deep it is, like the bars pages.
-- Each LATERAL subquery is a single probe of the bars primary key (symbol_id, ts): forward for the first bar,
-- backward for the last one, which also gives the latest close and the feed it came from.
SELECT s.ticker, s.name, s.source, f.ts AS first_bar, l.ts AS last_bar, l.close AS last_close, l.feed,
       s.last_ingested_at
FROM symbols s
CROSS JOIN LATERAL (SELECT ts FROM bars b WHERE b.symbol_id = s.id ORDER BY ts LIMIT 1) f
CROSS JOIN LATERAL (SELECT ts, close, feed FROM bars b WHERE b.symbol_id = s.id ORDER BY ts DESC LIMIT 1) l
WHERE s.source = ANY(CAST(:sources AS text[]))
  AND s.ticker LIKE :prefix || '%'
  AND s.ticker > :after
ORDER BY s.ticker
LIMIT :limit
