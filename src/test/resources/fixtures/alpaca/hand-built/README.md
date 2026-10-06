# Hand-built fixtures (not real market data)

These files were written by hand, before any Alpaca keys existed, so the split-adjustment test could run.
They use the exact JSON shape Alpaca documents for `GET /v2/stocks/bars` and `GET /v1/corporate-actions`,
but **the prices, volumes and trade counts are invented**. Only the split events are real:

| Symbol | Split      | Ex-date    |
|--------|------------|------------|
| AAPL   | 4-for-1    | 2020-08-31 |
| TSLA   | 5-for-1    | 2020-08-31 |
| TSLA   | 3-for-1    | 2022-08-25 |

- `bars_raw.json`: `adjustment=raw` response for AAPL and TSLA, 2020-08-24 to 2020-09-04.
- `bars_split.json`: the same bars as Alpaca's `adjustment=split` would return them, computed independently of
  the SQL view (Python `Decimal`: prices divided by the cumulative split factor and rounded half-up to 4 decimal
  places, volumes multiplied by it). TSLA's 2020 bars are divided by 15 because Alpaca adjusts relative to
  today, which includes the 2022 split.
- `corporate_actions.json`: the three splits above. The `id` values are placeholders.

Once keys exist, `scripts/record-fixtures.sh` writes real responses for the same request to `../recorded/`, and
`SplitAdjustedViewIT` runs the same assertions against them.
