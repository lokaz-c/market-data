SELECT id, ticker, name, source
FROM symbols
WHERE ticker = :ticker
  AND source = ANY(CAST(:sources AS text[]))
