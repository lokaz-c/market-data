package com.lokaz.marketdata.alpaca;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

/**
 * Client for the two Alpaca Market Data endpoints the service needs:
 * GET /v2/stocks/bars (daily bars, paginated with next_page_token) and
 * GET /v1/corporate-actions (forward and reverse splits). Every HTTP call goes through {@link Retrier}.
 */
@Component
public class AlpacaClient {

    private static final int MAX_ERROR_BODY_CHARS = 300;
    private static final int CORPORATE_ACTIONS_PAGE_LIMIT = 1_000; // documented maximum

    private final RestClient http;
    private final AlpacaProperties props;
    private final Retrier retrier;

    @Autowired
    public AlpacaClient(AlpacaProperties props, Clock clock) {
        this(props, Retrier.withThreadSleep(props.retry(), clock));
    }

    AlpacaClient(AlpacaProperties props, Retrier retrier) {
        this.props = props;
        this.retrier = retrier;
        var jdkClient = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(jdkClient);
        requestFactory.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder()
                .baseUrl(props.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("APCA-API-KEY-ID", nullToEmpty(props.keyId()))
                .defaultHeader("APCA-API-SECRET-KEY", nullToEmpty(props.secretKey()))
                .build();
    }

    /**
     * Fetches daily bars for {@code symbols} between {@code start} and {@code end} (both inclusive) and
     * passes each page to {@code pageConsumer}, so a long backfill never holds more than one page in memory.
     * Symbols are sent in chunks of {@code symbolsPerRequest}; each chunk is followed to its last page.
     */
    public void forEachDailyBarsPage(List<String> symbols, LocalDate start, LocalDate end, Adjustment adjustment,
            Retrier.RetryListener retryListener, Consumer<Map<String, List<AlpacaBar>>> pageConsumer) {
        for (List<String> chunk : chunks(symbols, props.symbolsPerRequest())) {
            String pageToken = null;
            do {
                AlpacaBarsResponse page = barsPage(chunk, start, end, adjustment, pageToken, retryListener);
                pageConsumer.accept(page.barsOrEmpty());
                pageToken = page.nextPageToken();
            } while (pageToken != null && !pageToken.isEmpty());
        }
    }

    /** Collects all pages of daily bars into one map; meant for small requests and tests. */
    public Map<String, List<AlpacaBar>> fetchDailyBars(List<String> symbols, LocalDate start, LocalDate end,
            Adjustment adjustment) {
        var all = new HashMap<String, List<AlpacaBar>>();
        forEachDailyBarsPage(symbols, start, end, adjustment, Retrier.RetryListener.NONE,
                page -> page.forEach((symbol, bars) -> all.computeIfAbsent(symbol, s -> new ArrayList<>()).addAll(bars)));
        return all;
    }

    /**
     * Forward and reverse splits for {@code symbols}. Alpaca filters corporate actions by process date, which
     * for splits is normally the ex-date.
     */
    public List<AlpacaSplit> fetchSplits(List<String> symbols, LocalDate start, LocalDate end,
            Retrier.RetryListener retryListener) {
        var splits = new ArrayList<AlpacaSplit>();
        for (List<String> chunk : chunks(symbols, props.symbolsPerRequest())) {
            String pageToken = null;
            do {
                CorporateActionsResponse page = splitsPage(chunk, start, end, pageToken, retryListener);
                splits.addAll(page.splits());
                pageToken = page.nextPageToken();
            } while (pageToken != null && !pageToken.isEmpty());
        }
        return splits;
    }

    private AlpacaBarsResponse barsPage(List<String> symbols, LocalDate start, LocalDate end, Adjustment adjustment,
            @Nullable String pageToken, Retrier.RetryListener retryListener) {
        // Values go in as URI variables so they are fully encoded; page tokens are base64 and may contain '+'.
        var vars = new HashMap<String, Object>();
        vars.put("symbols", String.join(",", symbols));
        vars.put("start", start.toString());
        vars.put("end", end.toString());
        vars.put("adjustment", adjustment.param());
        vars.put("feed", props.feed());
        vars.put("limit", props.pageLimit());
        String template = "/v2/stocks/bars?symbols={symbols}&timeframe=1Day&start={start}&end={end}"
                + "&adjustment={adjustment}&feed={feed}&limit={limit}&sort=asc";
        if (pageToken != null) {
            template += "&page_token={pageToken}";
            vars.put("pageToken", pageToken);
        }
        String uriTemplate = template;
        return retrier.call(
                () -> http.get().uri(uriTemplate, vars).exchange((req, res) -> read(req, res, AlpacaBarsResponse.class)),
                retryListener);
    }

    private CorporateActionsResponse splitsPage(List<String> symbols, LocalDate start, LocalDate end,
            @Nullable String pageToken, Retrier.RetryListener retryListener) {
        var vars = new HashMap<String, Object>();
        vars.put("symbols", String.join(",", symbols));
        vars.put("start", start.toString());
        vars.put("end", end.toString());
        vars.put("limit", CORPORATE_ACTIONS_PAGE_LIMIT);
        String template = "/v1/corporate-actions?symbols={symbols}&types=forward_split,reverse_split"
                + "&start={start}&end={end}&limit={limit}";
        if (pageToken != null) {
            template += "&page_token={pageToken}";
            vars.put("pageToken", pageToken);
        }
        String uriTemplate = template;
        return retrier.call(
                () -> http.get().uri(uriTemplate, vars).exchange((req, res) -> read(req, res, CorporateActionsResponse.class)),
                retryListener);
    }

    private static <T> T read(HttpRequest request, ConvertibleClientHttpResponse response, Class<T> type)
            throws IOException {
        int status = response.getStatusCode().value();
        if (status < 200 || status >= 300) {
            throw new AlpacaHttpException(status, errorBody(response), rateLimitReset(response.getHeaders().getFirst("X-RateLimit-Reset")));
        }
        T body = response.bodyTo(type);
        if (body == null) {
            throw new AlpacaHttpException(status, "empty body for " + request.getURI().getPath(), null);
        }
        return body;
    }

    private static String errorBody(ConvertibleClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            String body = new String(in.readNBytes(MAX_ERROR_BODY_CHARS), StandardCharsets.UTF_8);
            return body.strip();
        } catch (IOException e) {
            return "";
        }
    }

    static @Nullable Instant rateLimitReset(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(header.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static <T> List<List<T>> chunks(List<T> items, int size) {
        var chunks = new ArrayList<List<T>>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(List.copyOf(items.subList(i, Math.min(items.size(), i + size))));
        }
        return chunks;
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
