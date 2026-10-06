-- Freshness and provenance for API clients.
--
-- bars.feed               which Alpaca feed a bar was ingested from (iex or sip), set at ingestion.
-- symbols.last_ingested_*  the last successful ingestion run that included the symbol, set when the run finishes.
-- symbols.data_changed_at  changes whenever the symbol's bars or splits change; the validator behind the ETag on
--                          /v1/bars.

ALTER TABLE bars
    ADD COLUMN feed text CONSTRAINT bars_feed_valid CHECK (feed IN ('iex', 'sip'));

COMMENT ON COLUMN bars.feed IS
    'Alpaca feed the bar was ingested from. NULL for synthetic bars and for bars ingested before V3.';

-- last_ingested_at is a column rather than a query over ingestion_runs because the run log is keyed by run:
-- "the latest successful run that included this ticker" has to read every run that includes it, which for a
-- daily job is every run, and the list grows forever. The column is one read per symbol; the log keeps history.
ALTER TABLE symbols
    ADD COLUMN last_ingested_at      timestamptz,
    ADD COLUMN last_ingestion_run_id bigint REFERENCES ingestion_runs (id) ON DELETE SET NULL,
    -- now() is stable, so existing rows get the migration time without a table rewrite.
    ADD COLUMN data_changed_at       timestamptz NOT NULL DEFAULT now();

COMMENT ON COLUMN symbols.last_ingested_at IS
    'Finish time of the last successful ingestion run that included this symbol. NULL for synthetic symbols.';
COMMENT ON COLUMN symbols.data_changed_at IS
    'Start time of the last transaction that inserted, updated or deleted this symbol''s bars or splits. '
    'An opaque version: compare for equality, not order (concurrent transactions can commit out of start order).';

-- The view gains the feed column, and split_factors moves from a WITH clause into a derived table.
--
-- Why: PostgreSQL does not flatten a subquery that has a WITH list into the query around it, even when the CTE
-- itself would be inlined. The V2 view therefore always ran as a separate "Subquery Scan": an outer
-- ORDER BY ts DESC LIMIT 100 (GET /v1/bars?last=100) could not reach the bars primary key, so it read all of a
-- symbol's bars and kept the top 100 in a heap sort. As a derived table the view is flattened, the bars side is
-- an index scan backwards that stops after 100 rows, and split_factors stays the small inner side of the join.
-- See docs/explain.md. The view's columns and results are unchanged; feed is added at the end because
-- CREATE OR REPLACE VIEW can only append columns.
CREATE OR REPLACE VIEW bars_split_adjusted AS
SELECT b.symbol_id,
       b.ts,
       round(b.open  * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS open,
       round(b.high  * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS high,
       round(b.low   * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS low,
       round(b.close * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS close,
       round(b.volume * coalesce(f.cum_new, 1) / coalesce(f.cum_old, 1))::bigint AS volume,
       b.trade_count,
       round(b.vwap  * coalesce(f.cum_old, 1) / coalesce(f.cum_new, 1), 6) AS vwap,
       coalesce(f.cum_new, 1) / coalesce(f.cum_old, 1) AS split_factor,
       b.feed
FROM bars b
LEFT JOIN (
    -- One row per split: the factor for this split and every later one (window ordered by ex_date DESC) applies
    -- from the previous split's ex_date (lag) up to, but not including, this split's ex_date.
    SELECT symbol_id,
           lag(ex_date) OVER by_date_asc AS applies_from,   -- NULL: from the first bar
           ex_date                       AS applies_until,  -- exclusive
           product(old_rate) OVER later_splits AS cum_old,
           product(new_rate) OVER later_splits AS cum_new
    FROM splits
    WINDOW by_date_asc  AS (PARTITION BY symbol_id ORDER BY ex_date),
           later_splits AS (PARTITION BY symbol_id ORDER BY ex_date DESC
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
) f
       ON f.symbol_id = b.symbol_id
      AND b.ts < f.applies_until
      AND (f.applies_from IS NULL OR b.ts >= f.applies_from);

-- Moves symbols.data_changed_at for every symbol whose rows a statement inserted, updated or deleted.
--
-- Statement-level with a transition table, so one statement costs one UPDATE however many rows it wrote: the
-- synthetic generator's single INSERT ... SELECT of a million bars is one semi-join. Rows an upsert left alone
-- (ON CONFLICT ... DO UPDATE ... WHERE ... IS DISTINCT FROM) are not in the transition table, so an ingestion
-- re-run that changes nothing leaves every ETag as it was.
--
-- The IS DISTINCT FROM now() guard writes each symbol row at most once per transaction (now() is the
-- transaction's start time). Without it, a page of 10,000 batched upserts would stack 10,000 versions of the
-- same symbols row inside one transaction.
CREATE FUNCTION touch_symbol_data() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE symbols s
    SET data_changed_at = now()
    WHERE s.id IN (SELECT symbol_id FROM changed_rows)
      AND s.data_changed_at IS DISTINCT FROM now();
    RETURN NULL;
END
$$;

-- PostgreSQL only allows a transition table on a trigger with a single event, hence one trigger per event.
CREATE TRIGGER bars_inserted AFTER INSERT ON bars
    REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION touch_symbol_data();
CREATE TRIGGER bars_updated AFTER UPDATE ON bars
    REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION touch_symbol_data();
CREATE TRIGGER bars_deleted AFTER DELETE ON bars
    REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION touch_symbol_data();

-- A new or corrected split re-bases every split-adjusted price before it, so splits count as data changes too.
CREATE TRIGGER splits_inserted AFTER INSERT ON splits
    REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION touch_symbol_data();
CREATE TRIGGER splits_updated AFTER UPDATE ON splits
    REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION touch_symbol_data();
CREATE TRIGGER splits_deleted AFTER DELETE ON splits
    REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION touch_symbol_data();
