# Recorded fixtures

Real Alpaca responses, written by `scripts/record-fixtures.sh` (needs `ALPACA_API_KEY_ID` and
`ALPACA_API_SECRET_KEY`). `SplitAdjustedViewIT` runs against these files when they exist and skips this set
otherwise.

TODO(lorenzo): run `scripts/record-fixtures.sh` once you have Alpaca keys, then commit the three JSON files.
Check Alpaca's terms first: committing a few days of bars for two symbols as test data is personal,
non-commercial use, but the repo is meant to become public.
