-- SYNTHETIC DATA. Symbols S001..Snnn. Real US tickers contain no digits, so these cannot be mistaken
-- for real symbols, and source = 'synthetic' marks every row.
-- Parameters (JdbcClient named parameters; psql variables in scripts/explain.sh): :symbols
INSERT INTO symbols (ticker, name, source)
SELECT 'S' || lpad(n::text, 3, '0'), 'Synthetic ' || lpad(n::text, 3, '0'), 'synthetic'
FROM generate_series(1, :symbols) AS n
ON CONFLICT (ticker) DO NOTHING
