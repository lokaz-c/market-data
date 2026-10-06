#!/usr/bin/env bash
# Load test of the local docker compose stack with k6; writes docs/loadtest.md.
# Usage: make loadtest   (needs Docker, k6 and Python 3)
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT=market-data-loadtest
export APP_PORT=18080 DB_PORT=15432
# Never use real keys here: the test needs the synthetic dataset.
export ALPACA_API_KEY_ID= ALPACA_API_SECRET_KEY=
COMPOSE=(docker compose -p "$PROJECT" -f docker-compose.yml -f loadtest/compose.loadtest.yml)
mkdir -p loadtest/results

cleanup() { "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "Building and starting the stack (500 synthetic symbols x 10 years)..."
"${COMPOSE[@]}" up -d --build
echo -n "Waiting for readiness (seeding runs before the app reports ready)"
for _ in $(seq 1 120); do
  if curl -fs "http://localhost:${APP_PORT}/actuator/health/readiness" | grep -q '"UP"'; then break; fi
  echo -n "."; sleep 2
done
echo
curl -fs "http://localhost:${APP_PORT}/actuator/health/readiness" | grep -q '"UP"' || { echo "app not ready"; exit 1; }

DATASET=$("${COMPOSE[@]}" exec -T db psql -U marketdata -d marketdata -At -c \
  "SELECT (SELECT count(*) FROM symbols) || '|' || (SELECT count(*) FROM bars) || '|' || pg_size_pretty(pg_relation_size('bars')) || '|' || current_setting('server_version')")

# Sample container CPU and memory while k6 runs, to see which side is the bottleneck.
STATS=loadtest/results/docker-stats.txt
: > "$STATS"
( while true; do
    docker stats --no-stream --format "$(date +%s) {{.Name}} {{.CPUPerc}} {{.MemUsage}}" \
      "${PROJECT}-app-1" "${PROJECT}-db-1" >> "$STATS" 2>/dev/null || true
    sleep 3
  done ) &
SAMPLER=$!
disown "$SAMPLER"

echo "Running k6 (about 2.5 minutes)..."
K6_START=$(date +%s)
set +e
SUMMARY_PATH=loadtest/results/summary.json k6 run --quiet -e BASE_URL="http://localhost:${APP_PORT}" loadtest/k6.js
K6_EXIT=$?
set -e
kill "$SAMPLER" 2>/dev/null || true

python3 scripts/loadtest_report.py \
  --summary loadtest/results/summary.json \
  --dataset "$DATASET" \
  --k6-version "$(k6 version | head -1)" \
  --k6-exit "$K6_EXIT" \
  --stats "$STATS" \
  --k6-start "$K6_START" \
  --out docs/loadtest.md
echo "Wrote docs/loadtest.md"
