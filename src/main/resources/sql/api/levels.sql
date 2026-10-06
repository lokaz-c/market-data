-- Price levels as of the last session on or before :asOf, on split-adjusted prices.
-- Pivots are classic floor pivots computed from that session, for use in the next session:
--   P = (H + L + C) / 3,  R1 = 2P - L,  S1 = 2P - H,  R2 = P + (H - L),  S2 = P - (H - L),
--   R3 = H + 2(P - L),    S3 = L - 2(H - P)
WITH anchor AS (
    SELECT max(ts) AS as_of
    FROM bars
    WHERE symbol_id = :symbolId AND ts <= :asOf
), recent AS (
    -- The bounds are scalar subqueries, which PostgreSQL evaluates once (InitPlans) and can use as index
    -- conditions on (symbol_id, ts). Joining to the anchor CTE instead made them a filter applied after
    -- reading every bar of the symbol (see docs/explain.md). 364 days = 52 weeks, the same window as the
    -- RANGE frame in indicators.sql.
    SELECT ts, high, low, close,
           row_number() OVER (ORDER BY ts DESC) AS days_back
    FROM bars_split_adjusted
    WHERE symbol_id = :symbolId
      AND ts <= (SELECT as_of FROM anchor)
      AND ts >= (SELECT as_of FROM anchor) - 364
), summary AS (
    SELECT (SELECT as_of FROM anchor)                                AS as_of,
           (SELECT min(ts) FROM bars WHERE symbol_id = :symbolId)    AS first_ts,
           max(high)  FILTER (WHERE days_back = 1)   AS h,
           max(low)   FILTER (WHERE days_back = 1)   AS l,
           max(close) FILTER (WHERE days_back = 1)   AS c,
           count(*)   FILTER (WHERE days_back <= 20) AS n20,
           max(high)  FILTER (WHERE days_back <= 20) AS high20,
           min(low)   FILTER (WHERE days_back <= 20) AS low20,
           count(*)   FILTER (WHERE days_back <= 50) AS n50,
           max(high)  FILTER (WHERE days_back <= 50) AS high50,
           min(low)   FILTER (WHERE days_back <= 50) AS low50,
           max(high) AS high52w,
           min(low)  AS low52w
    FROM recent
    HAVING count(*) > 0  -- no row (404) when there is no bar on or before :asOf
), pivot AS (
    SELECT *, (h + l + c) / 3 AS p FROM summary
)
SELECT as_of, c AS close, h AS high, l AS low,
       round(p, 4)               AS p,
       round(2 * p - l, 4)       AS r1,
       round(2 * p - h, 4)       AS s1,
       round(p + (h - l), 4)     AS r2,
       round(p - (h - l), 4)     AS s2,
       round(h + 2 * (p - l), 4) AS r3,
       round(l - 2 * (h - p), 4) AS s3,
       CASE WHEN n20 = 20 THEN high20 END AS high20,
       CASE WHEN n20 = 20 THEN low20  END AS low20,
       CASE WHEN n50 = 50 THEN high50 END AS high50,
       CASE WHEN n50 = 50 THEN low50  END AS low50,
       CASE WHEN as_of - first_ts >= 364 THEN high52w END AS high52w,
       CASE WHEN as_of - first_ts >= 364 THEN low52w  END AS low52w
FROM pivot
