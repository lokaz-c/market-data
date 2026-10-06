package com.lokaz.marketdata.api;

import java.time.Duration;
import java.time.LocalDate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import com.lokaz.marketdata.api.Dtos.BarsPage;
import com.lokaz.marketdata.api.Dtos.IndicatorsPage;
import com.lokaz.marketdata.api.Dtos.Levels;
import com.lokaz.marketdata.api.Dtos.SplitList;
import com.lokaz.marketdata.api.Dtos.SymbolList;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Public read endpoints. Without an API key they only see public sources (synthetic data by default), are
 * rate-limited per IP, and return at most {@value #MAX_PAGE} rows per page: enough for the chart explorer,
 * not a bulk download.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "market data")
public class MarketDataController {

    static final String TICKER = "^[A-Za-z][A-Za-z0-9.]{0,9}$";
    static final int MAX_PAGE = 1000;
    static final int MAX_SYMBOLS_PAGE = 200;
    /** Daily data changes once a day; five minutes of caching is safe and saves the free-tier database. */
    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final MarketDataService service;

    public MarketDataController(MarketDataService service) {
        this.service = service;
    }

    @GetMapping("/symbols")
    @Operation(summary = "Symbols with data, keyset-paginated by ticker, optionally filtered by ticker prefix",
            description = "Each symbol has its first and last bar, the last close, the feed of the last bar and "
                    + "when it was last ingested. Follow nextAfter for the next page.")
    public ResponseEntity<SymbolList> symbols(
            @Parameter(description = "Ticker prefix, case-insensitive")
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9.]{0,10}$") String q,
            @Parameter(description = "Cursor: the nextAfter value from the previous page")
            @RequestParam(required = false) @Pattern(regexp = TICKER) String after,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_SYMBOLS_PAGE) int limit,
            HttpServletRequest request) {
        return ok(request).body(service.symbols(q, after, limit, ApiAccessFilter.sources(request)));
    }

    @GetMapping("/bars/{ticker}")
    @Operation(summary = "Daily bars, split-adjusted by default: a keyset page by date, or the latest N bars",
            description = "Responses carry a weak ETag that changes whenever the symbol's bars or splits change. "
                    + "Send it back in If-None-Match to get a 304 with no body when nothing has changed.")
    @ApiResponse(responseCode = "200", description = "Bars, oldest first",
            headers = @Header(name = "ETag", description = "Weak validator for If-None-Match"))
    @ApiResponse(responseCode = "304", description = "Not modified since the ETag in If-None-Match")
    public ResponseEntity<BarsPage> bars(
            @PathVariable @Pattern(regexp = TICKER) String ticker,
            @Parameter(description = "First date (default: one year before `to`)")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @Parameter(description = "Last date (default: the latest bar)")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @Parameter(description = "Cursor: the nextAfter value from the previous page")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate after,
            @Parameter(description = "Rows per page (default 500)")
            @RequestParam(required = false) @Min(1) @Max(MAX_PAGE) Integer limit,
            @Parameter(description = "Return the N latest bars on or before `to`, oldest first, as one page. "
                    + "Cannot be combined with from, after or limit.")
            @RequestParam(required = false) @Min(1) @Max(MAX_PAGE) Integer last,
            @RequestParam(defaultValue = "split") @Pattern(regexp = "split|raw") String adjustment,
            HttpServletRequest request, WebRequest webRequest) {
        BarsQuery query = BarsQuery.of(from, to, after, limit, last, adjustment.equals("split"));
        SymbolRef symbol = service.resolve(ticker, ApiAccessFilter.sources(request));
        String etag = MarketDataService.barsEtag(symbol, query);
        // Decided before any bars are read: the validator is one column of the symbol row just resolved.
        if (webRequest.checkNotModified(etag)) {
            return respond(HttpStatus.NOT_MODIFIED, request).eTag(etag).build();
        }
        return ok(request).eTag(etag).body(service.bars(symbol, query));
    }

    @GetMapping("/splits/{ticker}")
    @Operation(summary = "Stock splits, oldest first",
            description = "A new split re-bases every split-adjusted price before its ex-date. Clients that cache "
                    + "split-adjusted bars can compare this list with the one they cached against.")
    public ResponseEntity<SplitList> splits(@PathVariable @Pattern(regexp = TICKER) String ticker,
            HttpServletRequest request) {
        return ok(request).body(service.splits(ticker, ApiAccessFilter.sources(request)));
    }

    @GetMapping("/indicators/{ticker}")
    @Operation(summary = "SMA 20/50/200, 20-day volatility, ATR 14, 52-week range and pivots, per day")
    public ResponseEntity<IndicatorsPage> indicators(
            @PathVariable @Pattern(regexp = TICKER) String ticker,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate after,
            @RequestParam(defaultValue = "500") @Min(1) @Max(MAX_PAGE) int limit,
            HttpServletRequest request) {
        return ok(request).body(service.indicators(ticker, from, to, after, limit, ApiAccessFilter.sources(request)));
    }

    @GetMapping("/levels/{ticker}")
    @Operation(summary = "Pivots, 20/50-day highs and lows and the 52-week range as of a session")
    public ResponseEntity<Levels> levels(
            @PathVariable @Pattern(regexp = TICKER) String ticker,
            @Parameter(description = "Session date (default: the latest bar); the last session on or before it is used")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            HttpServletRequest request) {
        return ok(request).body(service.levels(ticker, asOf, ApiAccessFilter.sources(request)));
    }

    private static ResponseEntity.BodyBuilder ok(HttpServletRequest request) {
        return respond(HttpStatus.OK, request);
    }

    /** The caching headers every read shares; a 304 repeats them, as RFC 9110 asks. */
    private static ResponseEntity.BodyBuilder respond(HttpStatus status, HttpServletRequest request) {
        // Keyed responses may include non-public data, so only shared caches may store anonymous ones.
        CacheControl cache = ApiAccessFilter.isAuthenticated(request)
                ? CacheControl.maxAge(CACHE_FOR).cachePrivate()
                : CacheControl.maxAge(CACHE_FOR).cachePublic();
        return ResponseEntity.status(status).cacheControl(cache).varyBy(ApiAccessFilter.API_KEY_HEADER);
    }
}
