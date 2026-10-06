-- Core schema: tracked symbols, daily bars, stock splits and a log of ingestion runs.

CREATE TABLE symbols (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker      text        NOT NULL,
    name        text,
    -- 'synthetic' rows come from the demo/benchmark generator and must never be shown as real prices.
    source      text        NOT NULL DEFAULT 'alpaca',
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT symbols_ticker_key    UNIQUE (ticker),
    CONSTRAINT symbols_ticker_format CHECK (ticker ~ '^[A-Z][A-Z0-9.]{0,9}$'),
    CONSTRAINT symbols_source_valid  CHECK (source IN ('alpaca', 'synthetic'))
);

CREATE TABLE bars (
    symbol_id    integer       NOT NULL REFERENCES symbols (id),
    ts           date          NOT NULL,
    open         numeric(18,6) NOT NULL,
    high         numeric(18,6) NOT NULL,
    low          numeric(18,6) NOT NULL,
    close        numeric(18,6) NOT NULL,
    volume       bigint        NOT NULL,
    trade_count  bigint,
    vwap         numeric(18,6),
    ingested_at  timestamptz   NOT NULL DEFAULT now(),
    -- (symbol_id, ts) rather than (ts, symbol_id): every read is "one symbol over a date range",
    -- which this order serves as a single index range scan already sorted by ts.
    CONSTRAINT bars_pkey                 PRIMARY KEY (symbol_id, ts),
    CONSTRAINT bars_prices_positive      CHECK (open > 0 AND high > 0 AND low > 0 AND close > 0),
    CONSTRAINT bars_high_gte_low         CHECK (high >= low),
    CONSTRAINT bars_open_close_in_range  CHECK (open BETWEEN low AND high AND close BETWEEN low AND high),
    CONSTRAINT bars_volume_nonnegative   CHECK (volume >= 0),
    CONSTRAINT bars_trades_nonnegative   CHECK (trade_count >= 0),
    CONSTRAINT bars_vwap_positive        CHECK (vwap > 0)
);

COMMENT ON TABLE  bars    IS 'Unadjusted (raw) daily bars. Split-adjusted prices come from the bars_split_adjusted view.';
COMMENT ON COLUMN bars.ts IS 'Trading session date in America/New_York. Alpaca stamps daily bars at midnight New York time.';

CREATE TABLE splits (
    symbol_id  integer       NOT NULL REFERENCES symbols (id),
    ex_date    date          NOT NULL,
    -- A 4-for-1 split is old_rate = 1, new_rate = 4; a 1-for-10 reverse split is old_rate = 10, new_rate = 1.
    -- Both rates are kept (instead of one ratio) so adjustments stay exact for ratios like 1/3.
    old_rate   numeric(18,8) NOT NULL,
    new_rate   numeric(18,8) NOT NULL,
    source     text          NOT NULL,
    CONSTRAINT splits_pkey           PRIMARY KEY (symbol_id, ex_date),
    CONSTRAINT splits_rates_positive CHECK (old_rate > 0 AND new_rate > 0),
    CONSTRAINT splits_not_identity   CHECK (old_rate <> new_rate),
    CONSTRAINT splits_source_valid   CHECK (source IN ('alpaca', 'synthetic'))
);

CREATE TABLE ingestion_runs (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind            text        NOT NULL,
    status          text        NOT NULL DEFAULT 'running',
    started_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    range_start     date        NOT NULL,
    range_end       date        NOT NULL,
    symbols         text[]      NOT NULL,
    bars_received   integer     NOT NULL DEFAULT 0,
    -- Rows inserted or changed. Re-ingesting identical data upserts 0 rows.
    bars_upserted   integer     NOT NULL DEFAULT 0,
    -- Rows that failed validation (for example high < low) and were skipped.
    bars_rejected   integer     NOT NULL DEFAULT 0,
    splits_upserted integer     NOT NULL DEFAULT 0,
    http_retries    integer     NOT NULL DEFAULT 0,
    error           text,
    CONSTRAINT ingestion_runs_kind_valid    CHECK (kind IN ('daily', 'backfill')),
    CONSTRAINT ingestion_runs_status_valid  CHECK (status IN ('running', 'succeeded', 'failed')),
    CONSTRAINT ingestion_runs_range_valid   CHECK (range_start <= range_end),
    CONSTRAINT ingestion_runs_finish_valid  CHECK ((status = 'running') = (finished_at IS NULL)),
    CONSTRAINT ingestion_runs_counts_valid  CHECK (bars_received >= 0 AND bars_upserted >= 0 AND bars_rejected >= 0
                                                   AND splits_upserted >= 0 AND http_retries >= 0)
);
