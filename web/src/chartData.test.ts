import { describe, expect, it } from 'vitest';
import type { IndicatorRow, Levels } from './api';
import { formatPercent, formatPrice, levelLines, rangeStart, toCandles, toLine, toVolume } from './chartData';

const row = (date: string, sma20: number | null): IndicatorRow => ({
  date,
  close: 100,
  sma20,
  sma50: null,
  sma200: null,
  volatility20: null,
  atr14: null,
  high52w: null,
  low52w: null,
  pivot: null,
  r1: null,
  s1: null,
});

const levels: Levels = {
  ticker: 'S001',
  source: 'synthetic',
  asOf: '2026-10-02',
  close: 101,
  pivots: { basedOn: '2026-10-02', p: 100, r1: 102, r2: 104, r3: 106, s1: 98, s2: 96, s3: 94 },
  range20d: { high: 110, low: 90 },
  range50d: { high: 115, low: 85 },
  range52w: { high: null, low: null },
};

describe('toCandles / toVolume', () => {
  it('maps bars to chart rows keyed by date', () => {
    const bars = [{ date: '2026-10-01', open: 1, high: 2, low: 0.5, close: 1.5, volume: 1000 }];
    expect(toCandles(bars)).toEqual([{ time: '2026-10-01', open: 1, high: 2, low: 0.5, close: 1.5 }]);
    expect(toVolume(bars)).toEqual([{ time: '2026-10-01', value: 1000 }]);
  });
});

describe('toLine', () => {
  it('leaves out days before the moving average has a full window', () => {
    const rows = [row('2026-09-30', null), row('2026-10-01', 99.5), row('2026-10-02', 100.25)];
    expect(toLine(rows, 'sma20')).toEqual([
      { time: '2026-10-01', value: 99.5 },
      { time: '2026-10-02', value: 100.25 },
    ]);
  });
});

describe('levelLines', () => {
  it('draws pivots from R2 down to S2', () => {
    const lines = levelLines(levels, { pivots: true, ranges: false });
    expect(lines.map((l) => [l.title, l.price])).toEqual([
      ['R2', 104],
      ['R1', 102],
      ['P', 100],
      ['S1', 98],
      ['S2', 96],
    ]);
  });

  it('adds ranges that exist and skips ones without enough history', () => {
    const lines = levelLines(levels, { pivots: false, ranges: true });
    expect(lines.map((l) => l.title)).toEqual(['20D H', '20D L']);
    expect(lines.every((l) => l.kind === 'range')).toBe(true);
  });
});

describe('rangeStart', () => {
  it('goes back whole months from the last bar', () => {
    expect(rangeStart('2026-10-02', 12)).toBe('2025-10-02');
    expect(rangeStart('2026-10-02', 6)).toBe('2026-04-02');
    expect(rangeStart('2026-03-31', 1)).toBe('2026-03-03'); // Date.UTC rolls 31 Feb over into March
  });
});

describe('formatting', () => {
  it('formats prices with two decimals and missing values as a dash', () => {
    expect(formatPrice(1234.5)).toBe('1,234.50');
    expect(formatPrice(null)).toBe('-');
    expect(formatPercent(0.2345)).toBe('23.4%');
  });
});
