-- FIRST VERSION of api/levels.sql, kept only so scripts/explain.py can compare it with the current one. It runs
-- against bars_split_adjusted_v2, the view as it was when this query was replaced (scripts/explain.py creates it).
-- The date bounds come from a join to the anchor CTE. The V2 view has a WITH clause, so PostgreSQL does not
-- flatten it and cannot push a join condition into it: the bounds became a filter applied after reading every bar
-- of the symbol instead of conditions on the (symbol_id, ts) index scan. Scalar subqueries (api/levels.sql) are
-- evaluated once as InitPlans and pushed in like constants.

-- Price levels as of the last session on or before :asOf, on split-adjusted prices.
-- Pivots are classic floor pivots computed from that session, for use in the next session:
--   P = (H + L + C) / 3,  R1 = 2P - L,  S1 = 2P - H,  R2 = P + (H - L),  S2 = P - (H - L),
--   R3 = H + 2(P - L),    S3 = L - 2(H - P)
WITH anchor AS (
    SELECT max(ts) AS as_of,
           (SELECT min(ts) FROM bars WHERE symbol_id = :symbolId) AS first_ts
    FROM bars
    WHERE symbol_id = :symbolId AND ts <= :asOf
), recent AS (
    SELECT a.ts, a.high, a.low, a.close,
           row_number() OVER (ORDER BY a.ts DESC) AS days_back
    FROM bars_split_adjusted_v2 a, anchor
    WHERE a.symbol_id = :symbolId
      AND a.ts <= anchor.as_of
      AND a.ts >= anchor.as_of - INTERVAL '52 weeks'
), summary AS (
    SELECT anchor.as_of,
           anchor.first_ts,
           max(r.high)  FILTER (WHERE r.days_back = 1)   AS h,
           max(r.low)   FILTER (WHERE r.days_back = 1)   AS l,
           max(r.close) FILTER (WHERE r.days_back = 1)   AS c,
           count(*)     FILTER (WHERE r.days_back <= 20) AS n20,
           max(r.high)  FILTER (WHERE r.days_back <= 20) AS high20,
           min(r.low)   FILTER (WHERE r.days_back <= 20) AS low20,
           count(*)     FILTER (WHERE r.days_back <= 50) AS n50,
           max(r.high)  FILTER (WHERE r.days_back <= 50) AS high50,
           min(r.low)   FILTER (WHERE r.days_back <= 50) AS low50,
           max(r.high) AS high52w,
           min(r.low)  AS low52w
    FROM anchor JOIN recent r ON true
    GROUP BY anchor.as_of, anchor.first_ts
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
