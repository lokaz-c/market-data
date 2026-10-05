import type { IndicatorRow, Levels } from '../api';
import { formatPercent, formatPrice } from '../chartData';

interface Props {
  levels: Levels | null;
  latest: IndicatorRow | null;
}

export function LevelsPanel({ levels, latest }: Props) {
  if (!levels) return null;
  const p = levels.pivots;
  return (
    <div className="panels">
      <section className="card">
        <p className="label">Pivots for the next session</p>
        <p className="muted small">Classic floor pivots from the {p.basedOn} session: P = (H + L + C) / 3</p>
        <table>
          <tbody>
            <tr><th>R2</th><td>{formatPrice(p.r2)}</td><th>S1</th><td>{formatPrice(p.s1)}</td></tr>
            <tr><th>R1</th><td>{formatPrice(p.r1)}</td><th>S2</th><td>{formatPrice(p.s2)}</td></tr>
            <tr><th>P</th><td>{formatPrice(p.p)}</td><th>S3</th><td>{formatPrice(p.s3)}</td></tr>
          </tbody>
        </table>
      </section>
      <section className="card">
        <p className="label">Ranges as of {levels.asOf}</p>
        <table>
          <thead>
            <tr><th /><th className="num">High</th><th className="num">Low</th></tr>
          </thead>
          <tbody>
            <tr><th>20 days</th><td>{formatPrice(levels.range20d.high)}</td><td>{formatPrice(levels.range20d.low)}</td></tr>
            <tr><th>50 days</th><td>{formatPrice(levels.range50d.high)}</td><td>{formatPrice(levels.range50d.low)}</td></tr>
            <tr><th>52 weeks</th><td>{formatPrice(levels.range52w.high)}</td><td>{formatPrice(levels.range52w.low)}</td></tr>
          </tbody>
        </table>
      </section>
      <section className="card">
        <p className="label">Indicators</p>
        <table>
          <tbody>
            <tr><th>SMA 20</th><td>{formatPrice(latest?.sma20)}</td></tr>
            <tr><th>SMA 50</th><td>{formatPrice(latest?.sma50)}</td></tr>
            <tr><th>SMA 200</th><td>{formatPrice(latest?.sma200)}</td></tr>
            <tr><th>Volatility (20d, ann.)</th><td>{formatPercent(latest?.volatility20)}</td></tr>
            <tr><th>ATR 14</th><td>{formatPrice(latest?.atr14)}</td></tr>
          </tbody>
        </table>
      </section>
    </div>
  );
}
