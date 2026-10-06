-- Insert tickers we have not seen before and return the id of every requested ticker.
-- The outer SELECT on symbols uses the statement's snapshot, so it does not see rows inserted by the
-- CTE: each ticker comes back exactly once, from "inserted" if new or from "symbols" if it existed.
WITH requested AS (
    SELECT DISTINCT unnest(CAST(:tickers AS text[])) AS ticker
), inserted AS (
    INSERT INTO symbols (ticker, source)
    SELECT ticker, 'alpaca' FROM requested
    ON CONFLICT (ticker) DO NOTHING
    RETURNING id, ticker
)
SELECT id, ticker FROM inserted
UNION ALL
SELECT s.id, s.ticker FROM symbols s JOIN requested r ON r.ticker = s.ticker
