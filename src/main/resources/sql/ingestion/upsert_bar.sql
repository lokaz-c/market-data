-- Idempotent upsert of one raw daily bar. The WHERE clause skips the write when nothing changed, so
-- re-ingesting the same data reports 0 affected rows and creates no dead tuples. A bar re-ingested from another
-- feed (iex to sip) counts as a change.
INSERT INTO bars AS b (symbol_id, ts, open, high, low, close, volume, trade_count, vwap, feed)
VALUES (:symbolId, :ts, :open, :high, :low, :close, :volume, :tradeCount, :vwap, :feed)
ON CONFLICT (symbol_id, ts) DO UPDATE
SET open        = excluded.open,
    high        = excluded.high,
    low         = excluded.low,
    close       = excluded.close,
    volume      = excluded.volume,
    trade_count = excluded.trade_count,
    vwap        = excluded.vwap,
    feed        = excluded.feed,
    ingested_at = now()
WHERE (b.open, b.high, b.low, b.close, b.volume, b.trade_count, b.vwap, b.feed)
      IS DISTINCT FROM
      (excluded.open, excluded.high, excluded.low, excluded.close, excluded.volume, excluded.trade_count, excluded.vwap,
       excluded.feed)
