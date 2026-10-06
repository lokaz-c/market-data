-- SYNTHETIC DATA. A geometric random walk per symbol on weekdays (no holiday calendar):
--   close_t = start_price * exp(sum of daily log returns), log return ~ N(drift, vol)
-- open sits between the previous and current close, high/low extend beyond open and close, volume grows with
-- the size of the move. Prices before a synthetic split are scaled up by its ratio (and volume down), so the
-- raw series jumps at the ex-date and bars_split_adjusted should give back the smooth walk.
-- Rows are inserted date by date, like a daily ingestion job would write them.
-- Run setseed() first in the same transaction for repeatable output.
-- Parameters: :years, :endDate
INSERT INTO bars (symbol_id, ts, open, high, low, close, volume, trade_count, vwap)
WITH params AS (
    SELECT id AS symbol_id,
           20 + random() * 480         AS start_price,
           -0.0001 + random() * 0.0005 AS drift,       -- daily log drift: -2.5% to +10% a year
           0.008 + random() * 0.022    AS vol,         -- daily log volatility: 0.8% to 3%
           300000 + random() * 9700000 AS base_volume
    FROM symbols
    WHERE source = 'synthetic'
      AND NOT EXISTS (SELECT 1 FROM bars b WHERE b.symbol_id = symbols.id)
    ORDER BY id
), days AS (
    SELECT d::date AS ts
    FROM generate_series(CAST(:endDate AS date) - make_interval(years => :years), CAST(:endDate AS date),
                         INTERVAL '1 day') AS d
    WHERE extract(isodow FROM d) < 6
), shocks AS (
    SELECT p.symbol_id, d.ts, p.start_price, p.vol, p.base_volume,
           random_normal(p.drift, p.vol) AS log_return,
           random() AS intraday_share, random() AS up_wick, random() AS down_wick, random() AS volume_noise
    FROM params p CROSS JOIN days d
), walk AS (
    SELECT symbol_id, ts, vol, base_volume, log_return, intraday_share, up_wick, down_wick, volume_noise,
           start_price * exp(sum(log_return) OVER (PARTITION BY symbol_id ORDER BY ts)) AS close
    FROM shocks
), ohlc AS (
    SELECT w.symbol_id, w.ts,
           w.close / exp(w.log_return * w.intraday_share) AS open,
           w.close,
           w.vol, w.up_wick, w.down_wick,
           w.base_volume * (0.5 + w.volume_noise) * (1 + 25 * abs(w.log_return)) AS volume,
           coalesce(sp.new_rate / sp.old_rate, 1) AS split_ratio
    FROM walk w
    LEFT JOIN splits sp ON sp.symbol_id = w.symbol_id AND sp.source = 'synthetic' AND w.ts < sp.ex_date
)
SELECT symbol_id, ts,
       round(CAST(open * split_ratio AS numeric), 4),
       round(CAST(greatest(open, close) * (1 + vol * up_wick * 0.6) * split_ratio AS numeric), 4),
       round(CAST(least(open, close) * (1 - vol * down_wick * 0.6) * split_ratio AS numeric), 4),
       round(CAST(close * split_ratio AS numeric), 4),
       round(CAST(volume / split_ratio AS numeric))::bigint,
       round(CAST(volume / 120 AS numeric))::bigint,
       round(CAST((greatest(open, close) + least(open, close) + close) / 3 * split_ratio AS numeric), 4)
FROM ohlc
ORDER BY ts, symbol_id
