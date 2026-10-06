-- Symbols that have data, with their first and last bar. Each LATERAL subquery is a single probe of the
-- bars primary key (symbol_id, ts): forward for the first bar, backward for the last one.
SELECT s.ticker, s.name, s.source, f.ts AS first_bar, l.ts AS last_bar, l.close AS last_close
FROM symbols s
CROSS JOIN LATERAL (SELECT ts FROM bars b WHERE b.symbol_id = s.id ORDER BY ts LIMIT 1) f
CROSS JOIN LATERAL (SELECT ts, close FROM bars b WHERE b.symbol_id = s.id ORDER BY ts DESC LIMIT 1) l
WHERE s.source = ANY(CAST(:sources AS text[]))
  AND s.ticker LIKE :prefix || '%'
ORDER BY s.ticker
LIMIT :limit
