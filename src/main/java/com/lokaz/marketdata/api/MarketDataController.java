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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lokaz.marketdata.api.Dtos.BarsPage;
import com.lokaz.marketdata.api.Dtos.IndicatorsPage;
import com.lokaz.marketdata.api.Dtos.Levels;
import com.lokaz.marketdata.api.Dtos.SymbolList;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    /** Daily data changes once a day; five minutes of caching is safe and saves the free-tier database. */
    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final MarketDataService service;

    public MarketDataController(MarketDataService service) {
        this.service = service;
    }

    @GetMapping("/symbols")
    @Operation(summary = "Symbols with data, optionally filtered by ticker prefix")
    public ResponseEntity<SymbolList> symbols(
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9.]{0,10}$") String q,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            HttpServletRequest request) {
        return cached(service.symbols(q, limit, ApiAccessFilter.sources(request)), request);
    }

    @GetMapping("/bars/{ticker}")
    @Operation(summary = "Daily bars, split-adjusted by default, keyset-paginated by date")
    public ResponseEntity<BarsPage> bars(
            @PathVariable @Pattern(regexp = TICKER) String ticker,
            @Parameter(description = "First date (default: one year before `to`)")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @Parameter(description = "Last date (default: the latest bar)")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @Parameter(description = "Cursor: the nextAfter value from the previous page")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate after,
            @RequestParam(defaultValue = "500") @Min(1) @Max(MAX_PAGE) int limit,
            @RequestParam(defaultValue = "split") @Pattern(regexp = "split|raw") String adjustment,
            HttpServletRequest request) {
        return cached(service.bars(ticker, from, to, after, limit, adjustment.equals("split"),
                ApiAccessFilter.sources(request)), request);
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
        return cached(service.indicators(ticker, from, to, after, limit, ApiAccessFilter.sources(request)), request);
    }

    @GetMapping("/levels/{ticker}")
    @Operation(summary = "Pivots, 20/50-day highs and lows and the 52-week range as of a session")
    public ResponseEntity<Levels> levels(
            @PathVariable @Pattern(regexp = TICKER) String ticker,
            @Parameter(description = "Session date (default: the latest bar); the last session on or before it is used")
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            HttpServletRequest request) {
        return cached(service.levels(ticker, asOf, ApiAccessFilter.sources(request)), request);
    }

    private static <T> ResponseEntity<T> cached(T body, HttpServletRequest request) {
        // Keyed responses may include non-public data, so only shared caches may store anonymous ones.
        CacheControl cache = ApiAccessFilter.isAuthenticated(request)
                ? CacheControl.maxAge(CACHE_FOR).cachePrivate()
                : CacheControl.maxAge(CACHE_FOR).cachePublic();
        return ResponseEntity.ok().cacheControl(cache).varyBy(ApiAccessFilter.API_KEY_HEADER).body(body);
    }
}
