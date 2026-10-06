-- Exact product over NUMERIC. PostgreSQL has no product() aggregate; the usual exp(sum(ln(x)))
-- rounds in the last digits, so a cumulative split factor of 4 can come back as 3.99999...
CREATE AGGREGATE product(numeric) (
    SFUNC    = numeric_mul,
    STYPE    = numeric,
    INITCOND = '1'
);

-- Split-adjusted daily bars, relative to the latest split we know about (same convention as
-- Alpaca's adjustment=split). A bar's prices are multiplied by old_rate/new_rate of every split
-- whose ex_date is after the bar; volume is multiplied by the inverse.
--
-- split_factors turns the split list into date ranges: the factor for split k (the product of
-- split k and all later splits) applies to bars from the previous split's ex_date up to, but not
-- including, split k's ex_date. Bars after the latest split have no factor (1).
CREATE VIEW bars_split_adjusted AS
WITH split_factors AS (
    SELECT symbol_id,
           lag(ex_date) OVER by_date_asc AS applies_from,   -- NULL: from the first bar
           ex_date                       AS applies_until,  -- exclusive
           product(old_rate) OVER later_splits AS cum_old,
           product(new_rate) OVER later_splits AS cum_new
    FROM splits
    WINDOW by_date_asc  AS (PARTITION BY symbol_id ORDER BY ex_date),
           later_splits AS (PARTITION BY symbol_id ORDER BY ex_date DESC
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
)
SELECT b.symbol_id,
       b.ts,
       -- Multiply before dividing so there is one rounding step, then round to the column scale.
       round(b.open  * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS open,
       round(b.high  * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS high,
       round(b.low   * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS low,
       round(b.close * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS close,
       round(b.volume * coalesce(f.cum_new, 1) / coalesce(f.cum_old, 1))::bigint AS volume,
       b.trade_count,
       round(b.vwap  * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS vwap,
       coalesce(f.cum_new, 1) / coalesce(f.cum_old, 1) AS split_factor
FROM bars b
LEFT JOIN split_factors f
       ON f.symbol_id = b.symbol_id
      AND b.ts < f.applies_until
      AND (f.applies_from IS NULL OR b.ts >= f.applies_from);

COMMENT ON VIEW bars_split_adjusted IS
    'Daily bars adjusted for splits (not dividends). split_factor = new shares per old share for all splits after ts.';
