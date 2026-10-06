#!/usr/bin/env python3
"""Writes docs/explain.md: EXPLAIN (ANALYZE, BUFFERS) for the API's main queries on a synthetic dataset.

Run with `make explain`. Needs Docker and Python 3 (standard library only).

What it does:
  1. Starts a throwaway PostgreSQL 18 container (removed at the end).
  2. Applies the Flyway migrations as plain SQL, then drops the bars primary key: the state of a table
     that has been bulk-loaded before its index exists.
  3. Generates SYNTHETIC data with the same SQL the app's demo seeder uses (src/main/resources/sql/synthetic):
     500 symbols x 10 years of weekday bars, inserted date by date like a daily ingestion job.
  4. Runs each query from src/main/resources/sql/api (the exact files the service executes), plus two comparison
     queries from scripts/sql against the split-adjusted view as V2 defined it (bars_split_adjusted_v2), in three
     states:
       A. no index on bars;
       B. the primary key (symbol_id, ts) from V1__core_tables.sql;
       C. B plus CLUSTER bars USING bars_pkey (rows physically ordered by symbol, then date).
     Each query runs once to warm the cache, then 7 times; the table reports the median execution time.

Parameters are inlined as literals here; the service sends the same values as JDBC bind parameters.
"""

from __future__ import annotations

import datetime as dt
import json
import platform
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATIONS = ROOT / "src/main/resources/db/migration"
SQL = ROOT / "src/main/resources/sql"
OUT = ROOT / "docs/explain.md"

CONTAINER = "market-data-explain"
IMAGE = "postgres:18-alpine"
SYMBOLS = 500
YEARS = 10
END_DATE = "2026-10-02"
SEED = 0.42
RUNS = 7
TICKER = "S250"

# (title, SQL file, endpoint, parameter overrides). Limits are what the service sends: page size + 1 for keyset
# pages, N for last=N.
QUERIES = [
    ("Bars page: 1 year, split-adjusted", "src/main/resources/sql/api/bars_page_split.sql",
     "GET /v1/bars/{ticker}?from=2025-10-02&to=2026-10-02&limit=1000", {}),
    ("Latest 100 bars, split-adjusted", "src/main/resources/sql/api/bars_latest_split.sql",
     "GET /v1/bars/{ticker}?last=100", {"limit": "100"}),
    ("Latest 100 bars on the V2 view (WITH clause, not flattened)", "scripts/sql/bars_latest_v2_view.sql",
     "comparison: the view before V3", {"limit": "100"}),
    ("Indicators page: 1 year (window functions over 1 year + 400 days)", "src/main/resources/sql/api/indicators.sql",
     "GET /v1/indicators/{ticker}?from=2025-10-02&to=2026-10-02&limit=1000", {}),
    ("Levels as of the latest session", "src/main/resources/sql/api/levels.sql", "GET /v1/levels/{ticker}", {}),
    ("Levels, first version (bounds joined from a CTE), on the V2 view", "scripts/sql/levels_v1_anchor_join.sql",
     "comparison: replaced before V3", {}),
    ("Latest bar date (default `to`)", "src/main/resources/sql/api/latest_bar_date.sql",
     "every bars/indicators call without `to`", {}),
    ("Symbol lookup with its data version (the ETag check)", "src/main/resources/sql/api/find_symbol.sql",
     "every per-ticker call; all a 304 reads", {}),
    ("Splits of one symbol", "src/main/resources/sql/api/splits.sql", "GET /v1/splits/{ticker}", {}),
    ("Symbol list with first/last bar (LATERAL), first page", "src/main/resources/sql/api/list_symbols.sql",
     "GET /v1/symbols?limit=50", {"limit": "51"}),
    ("Symbol list, a later page (keyset on ticker)", "src/main/resources/sql/api/list_symbols.sql",
     "GET /v1/symbols?after=S400&limit=50", {"limit": "51", "after": "'S400'"}),
]

STATES = [
    ("A", "No index on bars (bulk-loaded heap)"),
    ("B", "Primary key (symbol_id, ts), as migrated"),
    ("C", "Primary key + CLUSTER bars USING bars_pkey"),
]


def run(cmd: list[str], stdin: str | None = None, check: bool = True) -> str:
    result = subprocess.run(cmd, input=stdin, capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"command failed: {' '.join(cmd)}\n{result.stderr}")
    return result.stdout


def psql(sql: str) -> str:
    return run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", "bench", "-X", "-q",
                "-v", "ON_ERROR_STOP=1", "-A", "-t"], stdin=sql)


def bind(sql: str, params: dict[str, str]) -> str:
    """Replaces :name placeholders (not ::casts) with SQL literals."""
    def repl(match: re.Match[str]) -> str:
        name = match.group(1)
        return params[name] if name in params else match.group(0)
    return re.sub(r"(?<!:):([A-Za-z]\w*)", repl, sql)


def machine() -> dict[str, str]:
    def sysctl(key: str) -> str:
        try:
            return run(["sysctl", "-n", key]).strip()
        except (FileNotFoundError, SystemExit):
            return "unknown"
    mem = sysctl("hw.memsize")
    docker = run(["docker", "info", "--format", "{{.NCPU}} CPUs, {{.MemTotal}} bytes"]).strip()
    cpus, _, mem_bytes = docker.partition(" CPUs, ")
    return {
        "cpu": sysctl("machdep.cpu.brand_string"),
        "memory": f"{int(mem) / 2**30:.0f} GiB" if mem.isdigit() else mem,
        "os": f"macOS {run(['sw_vers', '-productVersion']).strip()}" if platform.system() == "Darwin" else platform.platform(),
        "docker": f"{cpus} CPUs, {int(mem_bytes.split()[0]) / 2**30:.1f} GiB" if mem_bytes else docker,
    }


def start_database() -> None:
    run(["docker", "rm", "-f", CONTAINER], check=False)
    run(["docker", "run", "-d", "--name", CONTAINER, "-e", "POSTGRES_PASSWORD=postgres", "-e", "POSTGRES_DB=bench",
         IMAGE])
    for _ in range(60):
        if subprocess.run(["docker", "exec", CONTAINER, "pg_isready", "-U", "postgres", "-d", "bench"],
                          capture_output=True).returncode == 0:
            # pg_isready can succeed during the image's init restart; confirm with a query.
            if subprocess.run(["docker", "exec", CONTAINER, "psql", "-U", "postgres", "-d", "bench", "-c", "SELECT 1"],
                              capture_output=True).returncode == 0:
                return
        time.sleep(1)
    sys.exit("PostgreSQL did not start")


def v2_view() -> str:
    """The split-adjusted view as V2 defined it (split factors in a WITH clause), as bars_split_adjusted_v2.

    V3 replaced it because PostgreSQL never flattens a subquery that has a WITH list; the comparison queries in
    scripts/sql run against this copy to show the difference."""
    text = (MIGRATIONS / "V2__split_adjusted_view.sql").read_text()
    start = text.index("CREATE VIEW bars_split_adjusted AS")
    view = text[start:text.index(";", start) + 1]
    return view.replace("CREATE VIEW bars_split_adjusted AS", "CREATE VIEW bars_split_adjusted_v2 AS")


def load_data() -> float:
    for migration in sorted(MIGRATIONS.glob("V*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
        psql(migration.read_text())
    psql(v2_view())
    psql("ALTER TABLE bars DROP CONSTRAINT bars_pkey;")
    params = {"symbols": str(SYMBOLS), "years": str(YEARS), "endDate": f"'{END_DATE}'"}
    generator = "\n".join(bind((SQL / f"synthetic/{name}").read_text(), params) + ";"
                          for name in ("1_symbols.sql", "2_splits.sql", "3_bars.sql"))
    started = time.perf_counter()
    psql(f"BEGIN;\nSELECT setseed({SEED});\n{generator}\nCOMMIT;")
    elapsed = time.perf_counter() - started
    psql("VACUUM ANALYZE;")
    return elapsed


def dataset() -> dict[str, str]:
    row = psql("""
        SELECT (SELECT count(*) FROM symbols), (SELECT count(*) FROM bars), (SELECT count(*) FROM splits),
               (SELECT min(ts) FROM bars), (SELECT max(ts) FROM bars),
               pg_size_pretty(pg_relation_size('bars')), current_setting('shared_buffers'),
               current_setting('server_version');""").strip().split("|")
    keys = ["symbols", "bars", "splits", "first", "last", "heap", "shared_buffers", "version"]
    return dict(zip(keys, row))


def explain(sql: str) -> tuple[float, int, int, str]:
    """Returns (median execution ms, shared hit, shared read, text plan of the last run)."""
    times, hits, reads = [], 0, 0
    psql(f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {sql};")  # warm-up
    for _ in range(RUNS):
        plan = json.loads(psql(f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {sql};"))[0]
        times.append(plan["Execution Time"])
        hits, reads = plan["Plan"].get("Shared Hit Blocks", 0), plan["Plan"].get("Shared Read Blocks", 0)
    text = psql(f"EXPLAIN (ANALYZE, BUFFERS) {sql};").rstrip()
    return statistics.median(times), hits, reads, text


def main() -> None:
    print(f"Starting {IMAGE} as {CONTAINER} ...")
    start_database()
    try:
        print(f"Generating synthetic data: {SYMBOLS} symbols x {YEARS} years ...")
        generation_seconds = load_data()
        data = dataset()
        symbol_id = psql(f"SELECT id FROM symbols WHERE ticker = '{TICKER}';").strip()
        params = {
            "symbolId": symbol_id, "fromDate": "'2025-10-02'", "toDate": f"'{END_DATE}'", "limit": "1001",
            "asOf": f"'{END_DATE}'", "prefix": "''", "after": "''", "sources": "'{synthetic}'",
            "ticker": f"'{TICKER}'",
        }
        results: dict[str, dict[str, tuple[float, int, int, str]]] = {}
        for state, label in STATES:
            if state == "B":
                psql("ALTER TABLE bars ADD CONSTRAINT bars_pkey PRIMARY KEY (symbol_id, ts); ANALYZE bars;")
            if state == "C":
                psql("CLUSTER bars USING bars_pkey; ANALYZE bars;")
            print(f"State {state}: {label}")
            for title, file, _, overrides in QUERIES:
                sql = bind((ROOT / file).read_text(), dict(params, **overrides))
                results.setdefault(title, {})[state] = explain(sql)
                print(f"  {title}: {results[title][state][0]:.3f} ms")
        write_report(machine(), data, generation_seconds, results)
        print(f"Wrote {OUT.relative_to(ROOT)}")
    finally:
        run(["docker", "rm", "-f", CONTAINER], check=False)


def write_report(host: dict[str, str], data: dict[str, str], generation_seconds: float,
                 results: dict[str, dict[str, tuple[float, int, int, str]]]) -> None:
    lines = [
        "# Query plans before and after indexing",
        "",
        "Generated by `make explain` (`scripts/explain.py`). **Local run on synthetic data**, not production "
        "measurements: a throwaway PostgreSQL container on a laptop, with default settings.",
        "",
        f"- Date: {dt.date.today().isoformat()}",
        f"- Machine: {host['cpu']}, {host['memory']} RAM, {host['os']}; Docker VM: {host['docker']}",
        f"- PostgreSQL {data['version']} (`{IMAGE}`), shared_buffers = {data['shared_buffers']}",
        f"- Dataset (synthetic, `src/main/resources/sql/synthetic`, seed {SEED}): {int(data['symbols']):,} symbols, "
        f"{int(data['bars']):,} daily bars from {data['first']} to {data['last']}, {data['splits']} splits; "
        f"bars heap {data['heap']}. Generated in {generation_seconds:.1f} s, inserted date by date like a daily job.",
        f"- Queries: the service's own SQL files from `src/main/resources/sql/api`, for ticker {TICKER}, with "
        "parameters inlined as literals. Median of 7 warm runs; buffers are from the last run "
        "(8 kB pages; hit = found in shared buffers, read = fetched from the OS).",
        "",
        "States:",
        "",
    ]
    lines += [f"- **{state}**: {label}" for state, label in STATES]
    lines += [
        "",
        "## Summary",
        "",
        "| Query | Endpoint | A: no index | B: primary key | C: PK + CLUSTER |",
        "|---|---|---:|---:|---:|",
    ]
    for title, _, endpoint, _ in QUERIES:
        cells = []
        for state, _ in STATES:
            ms, hit, read = results[title][state][:3]
            cells.append(f"{ms:.2f} ms<br>{hit + read:,} buffers")
        where = f"`{endpoint}`" if endpoint.startswith("GET") else endpoint
        lines.append(f"| {title} | {where} | " + " | ".join(cells) + " |")
    lines += [
        "",
        "## Plans",
        "",
    ]
    for title, file, _, _ in QUERIES:
        lines += [f"### {title}", "", f"SQL: `{file}`", ""]
        for state, label in STATES:
            ms, hit, read, text = results[title][state]
            lines += [f"**{state}: {label}** (median {ms:.3f} ms, shared hit {hit:,}, read {read:,})", "", "```",
                      text, "```", ""]
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text("\n".join(lines))


if __name__ == "__main__":
    main()
