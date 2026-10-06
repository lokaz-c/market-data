-- Daily indicators for one symbol on split-adjusted prices, one keyset page at a time.
--
-- Window functions need history before the page, so `series` starts 400 calendar days before :fromDate
-- (more than 200 trading days and more than 52 weeks); the final WHERE trims back to the page.
-- A moving average is NULL until its window is full, rather than averaging fewer days.
WITH series AS (
    SELECT ts, high, low, close,
           lag(close) OVER by_ts AS prev_close,
           lag(high)  OVER by_ts AS prev_high,
           lag(low)   OVER by_ts AS prev_low,
           min(ts)    OVER ()    AS first_ts
    FROM bars_split_adjusted
    WHERE symbol_id = :symbolId
      AND ts BETWEEN CAST(:fromDate AS date) - 400 AND :toDate
    WINDOW by_ts AS (ORDER BY ts)
), daily AS (
    SELECT *,
           ln(close / prev_close) AS log_return,
           -- True range: the largest of today's range and the gaps from yesterday's close.
           greatest(high - low, abs(high - prev_close), abs(low - prev_close)) AS true_range
    FROM series
), indicators AS (
    SELECT ts, close, high, low, first_ts,
           CASE WHEN count(*) OVER w20  = 20  THEN avg(close) OVER w20  END AS sma20,
           CASE WHEN count(*) OVER w50  = 50  THEN avg(close) OVER w50  END AS sma50,
           CASE WHEN count(*) OVER w200 = 200 THEN avg(close) OVER w200 END AS sma200,
           -- Sample standard deviation of 20 daily log returns, annualised with sqrt(252).
           CASE WHEN count(log_return) OVER w20 = 20
                THEN stddev_samp(log_return) OVER w20 * sqrt(CAST(252 AS numeric)) END AS volatility20,
           -- ATR as the simple 14-day mean of true range (Wilder's smoothing would need recursion).
           CASE WHEN count(prev_close) OVER w14 = 14 THEN avg(true_range) OVER w14 END AS atr14,
           -- RANGE frame: calendar time, not a row count, so holidays do not stretch the window.
           max(high) OVER w52 AS high52w,
           min(low)  OVER w52 AS low52w,
           -- Classic floor pivot for this session, from the previous session's high, low and close.
           (prev_high + prev_low + prev_close) / 3 AS pivot,
           prev_high, prev_low
    FROM daily
    WINDOW w14  AS (ORDER BY ts ROWS BETWEEN 13  PRECEDING AND CURRENT ROW),
           w20  AS (ORDER BY ts ROWS BETWEEN 19  PRECEDING AND CURRENT ROW),
           w50  AS (ORDER BY ts ROWS BETWEEN 49  PRECEDING AND CURRENT ROW),
           w200 AS (ORDER BY ts ROWS BETWEEN 199 PRECEDING AND CURRENT ROW),
           w52  AS (ORDER BY ts RANGE BETWEEN INTERVAL '52 weeks' PRECEDING AND CURRENT ROW)
)
SELECT ts,
       close,
       round(sma20, 4)        AS sma20,
       round(sma50, 4)        AS sma50,
       round(sma200, 4)       AS sma200,
       round(volatility20, 6) AS volatility20,
       round(atr14, 4)        AS atr14,
       -- Only report a 52-week range once the symbol has 52 weeks of history.
       CASE WHEN ts - first_ts >= 364 THEN high52w END AS high52w,
       CASE WHEN ts - first_ts >= 364 THEN low52w  END AS low52w,
       round(pivot, 4)                    AS pivot,
       round(2 * pivot - prev_low, 4)     AS r1,
       round(2 * pivot - prev_high, 4)    AS s1
FROM indicators
WHERE ts BETWEEN :fromDate AND :toDate
ORDER BY ts
LIMIT :limit
