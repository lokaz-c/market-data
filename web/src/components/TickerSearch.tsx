import { useEffect, useState } from 'react';
import { fetchSymbols, type SymbolSummary } from '../api';
import { formatPrice } from '../chartData';

interface Props {
  selected: string | null;
  onSelect: (symbol: SymbolSummary) => void;
  onLoaded: (symbols: SymbolSummary[]) => void;
}

const TICKER_INPUT = /^[A-Za-z0-9.]{0,10}$/;

export function TickerSearch({ selected, onSelect, onLoaded }: Props) {
  const [query, setQuery] = useState('');
  const [symbols, setSymbols] = useState<SymbolSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Debounce typing so a search costs one request, not one per keystroke (the API is rate-limited).
    const timer = setTimeout(() => {
      fetchSymbols(query)
        .then((result) => {
          setSymbols(result);
          setError(null);
          if (query === '') onLoaded(result);
        })
        .catch((e: Error) => setError(e.message));
    }, query === '' ? 0 : 250);
    return () => clearTimeout(timer);
  }, [query, onLoaded]);

  return (
    <aside className="search">
      <label className="label" htmlFor="ticker-search">
        Ticker
      </label>
      <input
        id="ticker-search"
        className="input"
        placeholder="Search, e.g. S001"
        value={query}
        autoComplete="off"
        spellCheck={false}
        onChange={(e) => {
          if (TICKER_INPUT.test(e.target.value)) setQuery(e.target.value.toUpperCase());
        }}
      />
      {error && <p className="error">{error}</p>}
      <ul className="symbol-list">
        {symbols.map((s) => (
          <li key={s.ticker}>
            <button
              type="button"
              className={s.ticker === selected ? 'symbol active' : 'symbol'}
              onClick={() => onSelect(s)}
            >
              <span className="ticker">{s.ticker}</span>
              <span className="price">{formatPrice(s.lastClose)}</span>
            </button>
          </li>
        ))}
        {symbols.length === 0 && !error && <li className="muted small">No matching symbols.</li>}
      </ul>
    </aside>
  );
}
