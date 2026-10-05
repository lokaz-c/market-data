import { useEffect, useRef } from 'react';
import {
  CandlestickSeries,
  ColorType,
  createChart,
  HistogramSeries,
  LineSeries,
  LineStyle,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
} from 'lightweight-charts';
import type { Candle, LevelLine, Point, SmaKey } from '../chartData';

interface Props {
  candles: Candle[];
  volume: Point[];
  smas: Record<SmaKey, Point[]>;
  visibleSmas: Record<SmaKey, boolean>;
  levels: LevelLine[];
}

// Monochrome, to match lorenzokamanzi.com: hollow candles up, filled candles down, greys for averages.
const INK = '#000000';
const SMA_STYLE: Record<SmaKey, { color: string; width: 1 | 2 | 3 }> = {
  sma20: { color: '#000000', width: 1 },
  sma50: { color: '#6B6B6B', width: 2 },
  sma200: { color: '#A8A8A8', width: 3 },
};
const LEVEL_STYLE = {
  pivot: { color: '#000000', lineStyle: LineStyle.Dashed },
  resistance: { color: '#4D4D4D', lineStyle: LineStyle.Dotted },
  support: { color: '#4D4D4D', lineStyle: LineStyle.Dotted },
  range: { color: '#9A9A9A', lineStyle: LineStyle.SparseDotted },
} as const;

export function PriceChart({ candles, volume, smas, visibleSmas, levels }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const candleSeries = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volumeSeries = useRef<ISeriesApi<'Histogram'> | null>(null);
  const smaSeries = useRef<Partial<Record<SmaKey, ISeriesApi<'Line'>>>>({});
  const priceLines = useRef<IPriceLine[]>([]);

  // Create the chart once; later effects only swap data.
  useEffect(() => {
    if (!container.current) return;
    const c = createChart(container.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: '#FFFFFF' },
        textColor: INK,
        fontFamily: "'Inter Variable', Inter, system-ui, sans-serif",
        fontSize: 12,
        // Keeps the TradingView logo link required by the library's licence (the footer also credits it).
        attributionLogo: true,
      },
      grid: { vertLines: { color: '#F2F2F2' }, horzLines: { color: '#F2F2F2' } },
      rightPriceScale: { borderColor: '#E0E0E0' },
      timeScale: { borderColor: '#E0E0E0' },
    });
    candleSeries.current = c.addSeries(CandlestickSeries, {
      upColor: '#FFFFFF',
      downColor: INK,
      borderUpColor: INK,
      borderDownColor: INK,
      wickUpColor: INK,
      wickDownColor: INK,
    });
    volumeSeries.current = c.addSeries(HistogramSeries, {
      color: '#E3E3E3',
      priceFormat: { type: 'volume' },
      priceScaleId: 'volume',
      lastValueVisible: false,
      priceLineVisible: false,
    });
    c.priceScale('volume').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    for (const key of Object.keys(SMA_STYLE) as SmaKey[]) {
      smaSeries.current[key] = c.addSeries(LineSeries, {
        color: SMA_STYLE[key].color,
        lineWidth: SMA_STYLE[key].width,
        lastValueVisible: false,
        priceLineVisible: false,
        crosshairMarkerVisible: false,
      });
    }
    chart.current = c;
    return () => {
      c.remove();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    candleSeries.current?.setData(candles);
    volumeSeries.current?.setData(volume);
    chart.current?.timeScale().fitContent();
  }, [candles, volume]);

  useEffect(() => {
    for (const key of Object.keys(SMA_STYLE) as SmaKey[]) {
      const series = smaSeries.current[key];
      series?.setData(smas[key]);
      series?.applyOptions({ visible: visibleSmas[key] });
    }
  }, [smas, visibleSmas]);

  useEffect(() => {
    const series = candleSeries.current;
    if (!series) return;
    for (const line of priceLines.current) series.removePriceLine(line);
    priceLines.current = levels.map((level) =>
      series.createPriceLine({
        price: level.price,
        title: level.title,
        color: LEVEL_STYLE[level.kind].color,
        lineStyle: LEVEL_STYLE[level.kind].lineStyle,
        lineWidth: 1,
        axisLabelVisible: true,
      }),
    );
  }, [levels]);

  return <div className="chart" ref={container} data-testid="price-chart" />;
}
