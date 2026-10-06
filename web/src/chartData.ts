// Pure mapping from API rows to Lightweight Charts data, kept separate from React so it can be unit tested.
import type { Bar, IndicatorRow, Levels } from './api';

export interface Candle {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
}

export interface Point {
  time: string;
  value: number;
}

export type SmaKey = 'sma20' | 'sma50' | 'sma200';

export type LevelKind = 'pivot' | 'resistance' | 'support' | 'range';

export interface LevelLine {
  title: string;
  price: number;
  kind: LevelKind;
}

export function toCandles(bars: Bar[]): Candle[] {
  return bars.map((b) => ({ time: b.date, open: b.open, high: b.high, low: b.low, close: b.close }));
}

export function toVolume(bars: Bar[]): Point[] {
  return bars.map((b) => ({ time: b.date, value: b.volume }));
}

/** A moving average is null until its window is full; those days are left out rather than drawn at 0. */
export function toLine(rows: IndicatorRow[], key: SmaKey): Point[] {
  const points: Point[] = [];
  for (const row of rows) {
    const value = row[key];
    if (value !== null) points.push({ time: row.date, value });
  }
  return points;
}

/** Price lines for the levels panel: pivots always, 52-week and 20-day ranges when they exist. */
export function levelLines(levels: Levels, options: { pivots: boolean; ranges: boolean }): LevelLine[] {
  const lines: LevelLine[] = [];
  if (options.pivots) {
    const p = levels.pivots;
    lines.push(
      { title: 'R2', price: p.r2, kind: 'resistance' },
      { title: 'R1', price: p.r1, kind: 'resistance' },
      { title: 'P', price: p.p, kind: 'pivot' },
      { title: 'S1', price: p.s1, kind: 'support' },
      { title: 'S2', price: p.s2, kind: 'support' },
    );
  }
  if (options.ranges) {
    const ranges: [string, number | null][] = [
      ['52W H', levels.range52w.high],
      ['52W L', levels.range52w.low],
      ['20D H', levels.range20d.high],
      ['20D L', levels.range20d.low],
    ];
    for (const [title, price] of ranges) {
      if (price !== null) lines.push({ title, price, kind: 'range' });
    }
  }
  return lines;
}

/** First date of a lookback window ending at `to` (YYYY-MM-DD), in whole months. */
export function rangeStart(to: string, months: number): string {
  const [y, m, d] = to.split('-').map(Number);
  const start = new Date(Date.UTC(y, m - 1 - months, d));
  return start.toISOString().slice(0, 10);
}

export function formatPrice(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-';
  return value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-';
  return `${(value * 100).toFixed(1)}%`;
}
