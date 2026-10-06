import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  API_BASE,
  checkHealth,
  fetchBars,
  fetchIndicators,
  fetchLevels,
  type Bar,
  type IndicatorRow,
  type Levels,
  type SymbolSummary,
} from './api';
import { formatPrice, levelLines, rangeStart, toCandles, toLine, toVolume, type SmaKey } from './chartData';
import { LevelsPanel } from './components/LevelsPanel';
import { PriceChart } from './components/PriceChart';
import { TickerSearch } from './components/TickerSearch';
import { WakeScreen } from './components/WakeScreen';
import { waitForServer, type ServerState } from './wake';

const RANGES = [
  { label: '6M', months: 6 },
  { label: '1Y', months: 12 },
  { label: '2Y', months: 24 },
  { label: '5Y', months: 60 },
];
const SMA_LABELS: Record<SmaKey, string> = { sma20: 'SMA 20', sma50: 'SMA 50', sma200: 'SMA 200' };
const REPO_URL = 'https://github.com/lokaz-c/market-data';

interface ChartData {
  bars: Bar[];
  indicators: IndicatorRow[];
  levels: Levels;
}

function initialParam(name: string): string | null {
  return new URLSearchParams(window.location.search).get(name);
}

export default function App() {
  const [server, setServer] = useState<ServerState>({ kind: 'checking' });
  const [attempt, setAttempt] = useState(0);
  const [symbol, setSymbol] = useState<SymbolSummary | null>(null);
  const [months, setMonths] = useState(() => RANGES.find((r) => r.label === initialParam('range'))?.months ?? 12);
  const [visibleSmas, setVisibleSmas] = useState<Record<SmaKey, boolean>>({ sma20: true, sma50: true, sma200: true });
  const [showPivots, setShowPivots] = useState(true);
  const [showRanges, setShowRanges] = useState(true);
  // The last settled request, keyed by ticker and range; loading and errors are derived from it.
  const [result, setResult] = useState<{ key: string; data: ChartData | null; error: string | null } | null>(null);
  const [lastData, setLastData] = useState<ChartData | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void waitForServer({ check: () => checkHealth(), onState: setServer, signal: controller.signal });
    return () => controller.abort();
  }, [attempt]);

  // Pick the ticker from the URL, or the first symbol, once the list has loaded.
  const onSymbolsLoaded = useCallback((symbols: SymbolSummary[]) => {
    setSymbol((current) => current ?? symbols.find((s) => s.ticker === initialParam('ticker')) ?? symbols[0] ?? null);
  }, []);

  const requestKey = symbol ? `${symbol.ticker}|${months}` : null;

  useEffect(() => {
    if (!symbol) return;
    let cancelled = false;
    const key = `${symbol.ticker}|${months}`;
    const to = symbol.lastBar;
    const from = rangeStart(to, months);
    Promise.all([fetchBars(symbol.ticker, from, to), fetchIndicators(symbol.ticker, from, to), fetchLevels(symbol.ticker)])
      .then(([bars, indicators, levels]) => {
        if (cancelled) return;
        const data = { bars, indicators, levels };
        setResult({ key, data, error: null });
        setLastData(data);
      })
      .catch((e: Error) => {
        if (!cancelled) setResult({ key, data: null, error: e.message });
      });
    const label = RANGES.find((r) => r.months === months)?.label ?? '1Y';
    window.history.replaceState(null, '', `?ticker=${symbol.ticker}&range=${label}`);
    return () => {
      cancelled = true;
    };
  }, [symbol, months]);

  const loading = requestKey !== null && result?.key !== requestKey;
  const error = result?.key === requestKey ? result.error : null;
  // Keep the previous chart on screen while the next one loads.
  const data = lastData;

  const candles = useMemo(() => toCandles(data?.bars ?? []), [data]);
  const volume = useMemo(() => toVolume(data?.bars ?? []), [data]);
  const smas = useMemo(
    () => ({
      sma20: toLine(data?.indicators ?? [], 'sma20'),
      sma50: toLine(data?.indicators ?? [], 'sma50'),
      sma200: toLine(data?.indicators ?? [], 'sma200'),
    }),
    [data],
  );
  const lines = useMemo(
    () => (data ? levelLines(data.levels, { pivots: showPivots, ranges: showRanges }) : []),
    [data, showPivots, showRanges],
  );
  const latest = data?.indicators.at(-1) ?? null;
  const synthetic = symbol?.source === 'synthetic';

  return (
    <div className="page">
      <header className="header container">
        <div>
          <p className="label">Chart explorer</p>
          <h1>market-data</h1>
        </div>
        <nav>
          <a href={`${API_BASE}/docs`}>API docs</a>
          <a href={REPO_URL}>Source</a>
        </nav>
      </header>

      {server.kind !== 'ready' ? (
        <main className="container">
          <WakeScreen state={server} onRetry={() => setAttempt((n) => n + 1)} />
        </main>
      ) : (
        <main className="container layout">
          <TickerSearch selected={symbol?.ticker ?? null} onSelect={setSymbol} onLoaded={onSymbolsLoaded} />
          <section className="main">
            {synthetic && (
              <p className="banner" role="note">
                Synthetic data. These prices are generated for this demo and are not real market data.
              </p>
            )}
            <div className="title-row">
              <div>
                <h2>{symbol?.ticker ?? '-'}</h2>
                <p className="muted">
                  {symbol?.name ?? ''} {data ? `· close ${formatPrice(data.levels.close)} on ${data.levels.asOf}` : ''}
                </p>
              </div>
              <div className="controls">
                <div className="segmented" role="group" aria-label="Range">
                  {RANGES.map((r) => (
                    <button
                      key={r.label}
                      type="button"
                      className={r.months === months ? 'active' : ''}
                      onClick={() => setMonths(r.months)}
                    >
                      {r.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <div className="toggles">
              {(Object.keys(SMA_LABELS) as SmaKey[]).map((key) => (
                <label key={key} className={`toggle ${key}`}>
                  <input
                    type="checkbox"
                    checked={visibleSmas[key]}
                    onChange={(e) => setVisibleSmas((v) => ({ ...v, [key]: e.target.checked }))}
                  />
                  <span className="swatch" aria-hidden="true" />
                  {SMA_LABELS[key]}
                </label>
              ))}
              <label className="toggle">
                <input type="checkbox" checked={showPivots} onChange={(e) => setShowPivots(e.target.checked)} />
                Pivots
              </label>
              <label className="toggle">
                <input type="checkbox" checked={showRanges} onChange={(e) => setShowRanges(e.target.checked)} />
                52W / 20D range
              </label>
              {loading && <span className="muted small">Loading…</span>}
            </div>
            {error && <p className="error">{error}</p>}
            <PriceChart candles={candles} volume={volume} smas={smas} visibleSmas={visibleSmas} levels={lines} />
            <LevelsPanel levels={data?.levels ?? null} latest={latest} />
          </section>
        </main>
      )}

      <footer className="footer container">
        <p>
          {synthetic || !symbol
            ? 'Data: synthetic, generated in PostgreSQL for this demo.'
            : 'Data: daily bars from Alpaca Market Data (IEX feed), split-adjusted. IEX is one exchange, so volume is not consolidated.'}
        </p>
        <p>
          Charts: TradingView Lightweight Charts™. Copyright (c) 2025 TradingView, Inc.{' '}
          <a href="https://www.tradingview.com/">https://www.tradingview.com/</a>
        </p>
        <p>
          Built by <a href="https://lorenzokamanzi.com">Lorenzo Kamanzi</a>.
        </p>
      </footer>
    </div>
  );
}
