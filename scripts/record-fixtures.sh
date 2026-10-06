#!/usr/bin/env bash
# Records real Alpaca responses for the split-adjustment test (SplitAdjustedViewIT).
#
# Writes src/test/resources/fixtures/alpaca/recorded/{bars_raw,bars_split,corporate_actions}.json for the
# same request as the hand-built fixtures: AAPL and TSLA, 2020-08-24 to 2020-09-04, across AAPL's 4-for-1
# and TSLA's 5-for-1 splits (both ex-date 2020-08-31).
#
# Usage: ALPACA_API_KEY_ID=... ALPACA_API_SECRET_KEY=... scripts/record-fixtures.sh
#        (or put the keys in .env)
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a; source .env; set +a
fi
: "${ALPACA_API_KEY_ID:?set ALPACA_API_KEY_ID}"
: "${ALPACA_API_SECRET_KEY:?set ALPACA_API_SECRET_KEY}"

BASE="${ALPACA_DATA_URL:-https://data.alpaca.markets}"
FEED="${ALPACA_FEED:-iex}"
SYMBOLS="AAPL,TSLA"
START="2020-08-24"
END="2020-09-04"
# The service asks for corporate actions from 30 days before the range up to today; record the same window.
CA_START="2020-07-25"
TODAY="$(date +%Y-%m-%d)"
OUT="src/test/resources/fixtures/alpaca/recorded"

get() {
  curl --fail-with-body --silent --show-error \
    -H "APCA-API-KEY-ID: ${ALPACA_API_KEY_ID}" \
    -H "APCA-API-SECRET-KEY: ${ALPACA_API_SECRET_KEY}" \
    "$1"
}

pretty() {
  python3 -m json.tool --indent 2
}

for adjustment in raw split; do
  get "${BASE}/v2/stocks/bars?symbols=${SYMBOLS}&timeframe=1Day&start=${START}&end=${END}&adjustment=${adjustment}&feed=${FEED}&limit=10000&sort=asc" \
    | pretty > "${OUT}/bars_${adjustment}.json"
done
get "${BASE}/v1/corporate-actions?symbols=${SYMBOLS}&types=forward_split,reverse_split&start=${CA_START}&end=${TODAY}&limit=1000" \
  | pretty > "${OUT}/corporate_actions.json"

# The test assumes one page per request; fail loudly if Alpaca paginated.
for f in "${OUT}"/*.json; do
  if python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1])).get("next_page_token") is None else 1)' "$f"; then
    :
  else
    echo "error: $f has a next_page_token; the fixture needs a single page" >&2
    exit 1
  fi
done

echo "Recorded fixtures in ${OUT} (feed=${FEED}). Run ./mvnw verify to check the view against them."
