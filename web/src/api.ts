// Typed client for the market-data REST API. Same origin by default; set VITE_API_BASE_URL when the UI is
// hosted separately from the API (for example on a static host that does not sleep).

export type Source = 'alpaca' | 'synthetic';

export interface SymbolSummary {
  ticker: string;
  name: string | null;
  source: Source;
  firstBar: string;
  lastBar: string;
  lastClose: number;
}

export interface Bar {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface IndicatorRow {
  date: string;
  close: number;
  sma20: number | null;
  sma50: number | null;
  sma200: number | null;
  volatility20: number | null;
  atr14: number | null;
  high52w: number | null;
  low52w: number | null;
  pivot: number | null;
  r1: number | null;
  s1: number | null;
}

export interface Range {
  high: number | null;
  low: number | null;
}

export interface Levels {
  ticker: string;
  source: Source;
  asOf: string;
  close: number;
  pivots: { basedOn: string; p: number; r1: number; r2: number; r3: number; s1: number; s2: number; s3: number };
  range20d: Range;
  range50d: Range;
  range52w: Range;
}

interface Page {
  nextAfter: string | null;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export const API_BASE: string = import.meta.env.VITE_API_BASE_URL ?? '';

/** Largest page the public API serves. */
const PAGE_SIZE = 1000;
/** Guards against a runaway loop; five years of daily bars fit in two pages. */
const MAX_PAGES = 10;

async function getJson<T>(path: string, fetchFn: typeof fetch = fetch): Promise<T> {
  const response = await fetchFn(API_BASE + path, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    // Errors are RFC 9457 problem details; fall back to the status text if the body is not JSON.
    let detail = response.statusText;
    try {
      const problem = (await response.json()) as { detail?: string; title?: string };
      detail = problem.detail ?? problem.title ?? detail;
    } catch {
      // keep statusText
    }
    throw new ApiError(response.status, detail || `HTTP ${response.status}`);
  }
  return (await response.json()) as T;
}

/** Follows keyset pagination (nextAfter) and concatenates every page's rows. */
export async function fetchAllPages<P extends Page, R>(
  basePath: string,
  rows: (page: P) => R[],
  fetchFn: typeof fetch = fetch,
): Promise<{ first: P; rows: R[] }> {
  const separator = basePath.includes('?') ? '&' : '?';
  const all: R[] = [];
  let after: string | null = null;
  let first: P | undefined;
  for (let i = 0; i < MAX_PAGES; i++) {
    const path: string = `${basePath}${separator}limit=${PAGE_SIZE}${after ? `&after=${after}` : ''}`;
    const page: P = await getJson<P>(path, fetchFn);
    first ??= page;
    all.push(...rows(page));
    after = page.nextAfter;
    if (!after) break;
  }
  return { first: first as P, rows: all };
}

export function fetchSymbols(query: string, fetchFn: typeof fetch = fetch): Promise<SymbolSummary[]> {
  const q = encodeURIComponent(query.trim());
  return getJson<{ symbols: SymbolSummary[] }>(`/v1/symbols?limit=50&q=${q}`, fetchFn).then((r) => r.symbols);
}

export async function fetchBars(ticker: string, from: string, to: string, fetchFn: typeof fetch = fetch): Promise<Bar[]> {
  const result = await fetchAllPages<Page & { bars: Bar[] }, Bar>(
    `/v1/bars/${encodeURIComponent(ticker)}?from=${from}&to=${to}`,
    (page) => page.bars,
    fetchFn,
  );
  return result.rows;
}

export async function fetchIndicators(
  ticker: string,
  from: string,
  to: string,
  fetchFn: typeof fetch = fetch,
): Promise<IndicatorRow[]> {
  const result = await fetchAllPages<Page & { indicators: IndicatorRow[] }, IndicatorRow>(
    `/v1/indicators/${encodeURIComponent(ticker)}?from=${from}&to=${to}`,
    (page) => page.indicators,
    fetchFn,
  );
  return result.rows;
}

export function fetchLevels(ticker: string, fetchFn: typeof fetch = fetch): Promise<Levels> {
  return getJson<Levels>(`/v1/levels/${encodeURIComponent(ticker)}`, fetchFn);
}

/** True when the API answers its health check with status UP within the timeout. */
export async function checkHealth(timeoutMs = 5000, fetchFn: typeof fetch = fetch): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchFn(`${API_BASE}/actuator/health`, { signal: controller.signal, cache: 'no-store' });
    if (!response.ok) return false;
    const body = (await response.json()) as { status?: string };
    return body.status === 'UP';
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}
