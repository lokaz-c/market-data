-- data_changed_at is the validator for conditional bars requests: one probe of symbols_ticker_key decides a
-- 304 before any bars are read.
SELECT id, ticker, name, source, data_changed_at
FROM symbols
WHERE ticker = :ticker
  AND source = ANY(CAST(:sources AS text[]))
