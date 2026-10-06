package com.lokaz.marketdata.api;

import java.io.Writer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.lokaz.marketdata.api.Dtos.Bar;
import com.lokaz.marketdata.api.Dtos.BarsPage;
import com.lokaz.marketdata.api.Dtos.IndicatorRow;
import com.lokaz.marketdata.api.Dtos.IndicatorsPage;
import com.lokaz.marketdata.api.Dtos.Levels;
import com.lokaz.marketdata.api.Dtos.SplitList;
import com.lokaz.marketdata.api.Dtos.SymbolList;
import com.lokaz.marketdata.api.Dtos.SymbolSummary;
import com.lokaz.marketdata.ingestion.Tickers;

/**
 * Resolves symbols the caller may see, applies date defaults and keyset pagination, and delegates to SQL.
 *
 * <p>Pagination is keyset on ts: a page asks for {@code limit + 1} rows starting at its lower bound; if the
 * extra row comes back, the response carries {@code nextAfter} = the last returned date, and the next request
 * starts the day after it. Unlike OFFSET this costs the same for page 1 and page 50, and rows inserted by the
 * daily job cannot shift pages under a client. /v1/symbols pages the same way on the ticker.
 */
@Service
public class MarketDataService {

    /** Default window when the caller gives no dates: one year back from the latest bar. */
    private static final int DEFAULT_LOOKBACK_DAYS = 365;

    private final MarketDataRepository repository;
    private final TransactionTemplate readOnly;

    public MarketDataService(MarketDataRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.readOnly = new TransactionTemplate(transactions.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    public SymbolList symbols(@Nullable String query, @Nullable String after, int limit, List<String> sources) {
        Page<SymbolSummary, String> page = Page.of(
                repository.listSymbols(upper(query), upper(after), sources, limit + 1), limit, SymbolSummary::ticker);
        return new SymbolList(page.items(), page.nextAfter());
    }

    public BarsPage bars(SymbolRef symbol, BarsQuery query) {
        if (query.last() != null) {
            return latestBars(symbol, query, query.last());
        }
        Window window = window(symbol, query.from(), query.to(), query.after());
        List<Bar> rows = window.isEmpty() ? List.of() : repository.barsPage(symbol.id(), query.splitAdjusted(),
                window.lower(), window.to(), query.limit() + 1);
        Page<Bar, LocalDate> page = Page.of(rows, query.limit(), Bar::date);
        return new BarsPage(symbol.ticker(), symbol.source(), query.adjustment(), window.from(), window.to(),
                page.items(), page.nextAfter());
    }

    /**
     * The {@code last} latest bars on or before {@code to}: one page, oldest first, with no cursor. {@code from}
     * in the response is the first bar returned.
     */
    private BarsPage latestBars(SymbolRef symbol, BarsQuery query, int last) {
        LocalDate to = query.to() != null ? query.to() : repository.latestBarDate(symbol.id()).orElse(LocalDate.now());
        List<Bar> rows = repository.latestBars(symbol.id(), query.splitAdjusted(), to, last);
        LocalDate from = rows.isEmpty() ? to : rows.getFirst().date();
        return new BarsPage(symbol.ticker(), symbol.source(), query.adjustment(), from, to, rows, null);
    }

    /**
     * Weak ETag for a bars response: a hash of the symbol's data version and the request (the record's toString
     * lists every parameter). The version changes whenever the symbol's bars or splits change, so a 304 never
     * hides new data, and checking it reads one symbols row instead of any bars. Weak, because gzip and identity
     * encodings of the same JSON are equivalent, not byte-identical.
     */
    static String barsEtag(SymbolRef symbol, BarsQuery query) {
        String key = String.join("|", "bars-v1", Integer.toString(symbol.id()),
                symbol.dataChangedAt().toInstant().toString(), query.toString());
        return "W/\"" + HexFormat.of().formatHex(ApiKeys.sha256(key), 0, 16) + "\"";
    }

    public SplitList splits(String rawTicker, List<String> sources) {
        SymbolRef symbol = resolve(rawTicker, sources);
        return new SplitList(symbol.ticker(), symbol.source(), repository.splits(symbol.id()));
    }

    public IndicatorsPage indicators(String rawTicker, @Nullable LocalDate from, @Nullable LocalDate to,
            @Nullable LocalDate after, int limit, List<String> sources) {
        SymbolRef symbol = resolve(rawTicker, sources);
        Window window = window(symbol, from, to, after);
        List<IndicatorRow> rows = window.isEmpty() ? List.of()
                : repository.indicatorsPage(symbol.id(), window.lower(), window.to(), limit + 1);
        Page<IndicatorRow, LocalDate> page = Page.of(rows, limit, IndicatorRow::date);
        return new IndicatorsPage(symbol.ticker(), symbol.source(), window.from(), window.to(), page.items(),
                page.nextAfter());
    }

    public Levels levels(String rawTicker, @Nullable LocalDate asOf, List<String> sources) {
        SymbolRef symbol = resolve(rawTicker, sources);
        LocalDate date = asOf != null ? asOf : repository.latestBarDate(symbol.id())
                .orElseThrow(() -> new ApiExceptions.NotFound("No bars for " + symbol.ticker() + " yet."));
        return repository.levels(symbol, date)
                .orElseThrow(() -> new ApiExceptions.NotFound("No bars for " + symbol.ticker() + " on or before " + date + "."));
    }

    /** Resolves every ticker first, so an unknown ticker fails before any CSV is written. */
    public List<SymbolRef> resolveAll(List<String> rawTickers, List<String> sources) {
        var symbols = new ArrayList<SymbolRef>();
        for (String ticker : rawTickers) {
            symbols.add(resolve(ticker, sources));
        }
        return symbols;
    }

    public void exportCsv(List<SymbolRef> symbols, LocalDate from, LocalDate to, boolean splitAdjusted, Writer out) {
        // PostgreSQL only streams with a fetch size inside a transaction.
        readOnly.executeWithoutResult(status -> {
            for (SymbolRef symbol : symbols) {
                repository.exportCsv(symbol, splitAdjusted, from, to, out);
            }
        });
    }

    SymbolRef resolve(String rawTicker, List<String> sources) {
        String ticker;
        try {
            ticker = Tickers.normalize(rawTicker);
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequest(e.getMessage());
        }
        // Same 404 for "does not exist" and "not visible without a key": existence is not leaked.
        return repository.findSymbol(ticker, sources)
                .orElseThrow(() -> new ApiExceptions.NotFound("Unknown ticker: " + ticker));
    }

    private Window window(SymbolRef symbol, @Nullable LocalDate from, @Nullable LocalDate to, @Nullable LocalDate after) {
        LocalDate end = to != null ? to : repository.latestBarDate(symbol.id()).orElse(LocalDate.now());
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_LOOKBACK_DAYS);
        if (start.isAfter(end)) {
            throw new ApiExceptions.BadRequest("from (" + start + ") is after to (" + end + ").");
        }
        LocalDate lower = after == null || after.isBefore(start) ? start : after.plusDays(1);
        return new Window(start, end, lower);
    }

    /** The requested range plus the lower bound of the current page. */
    private record Window(LocalDate from, LocalDate to, LocalDate lower) {
        boolean isEmpty() {
            return lower.isAfter(to);
        }
    }

    private static String upper(@Nullable String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    /** Keyset page: {@code rows} holds up to limit + 1 rows; the extra one only says that another page exists. */
    private record Page<T, K>(List<T> items, @Nullable K nextAfter) {
        static <T, K> Page<T, K> of(List<T> rows, int limit, Function<T, K> key) {
            if (rows.size() <= limit) {
                return new Page<>(rows, null);
            }
            List<T> items = rows.subList(0, limit);
            return new Page<>(List.copyOf(items), key.apply(items.getLast()));
        }
    }
}
