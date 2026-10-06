package com.lokaz.marketdata.api;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.lokaz.marketdata.ingestion.Tickers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Bulk CSV export for the backtester. Requires X-API-Key (enforced by ApiAccessFilter). */
@RestController
@Tag(name = "export")
public class ExportController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final MarketDataService service;
    private final int maxSymbols;

    public ExportController(MarketDataService service, ApiProperties properties) {
        this.service = service;
        this.maxSymbols = properties.maxExportSymbols();
    }

    @GetMapping(value = "/v1/export/bars.csv", produces = "text/csv")
    @Operation(summary = "Stream daily bars for several symbols as CSV",
            description = "Columns: ticker,date,open,high,low,close,volume,source,feed. source is alpaca or "
                    + "synthetic; feed is the Alpaca feed (iex or sip), empty for synthetic data.",
            security = @SecurityRequirement(name = "apiKey"))
    public ResponseEntity<StreamingResponseBody> exportBars(
            @RequestParam @NotBlank String symbols,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "split") @Pattern(regexp = "split|raw") String adjustment,
            HttpServletRequest request) {
        List<String> tickers;
        try {
            tickers = Tickers.normalizeAll(Arrays.asList(symbols.split(",")));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequest(e.getMessage());
        }
        if (tickers.size() > maxSymbols) {
            throw new ApiExceptions.BadRequest("At most " + maxSymbols + " symbols per export, got " + tickers.size() + ".");
        }
        if (from.isAfter(to)) {
            throw new ApiExceptions.BadRequest("from (" + from + ") is after to (" + to + ").");
        }
        var resolved = service.resolveAll(tickers, ApiAccessFilter.sources(request));
        boolean splitAdjusted = adjustment.equals("split");
        StreamingResponseBody body = out -> {
            var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            // source: alpaca or synthetic, per symbol. feed: the Alpaca feed per bar (iex or sip), empty when not
            // recorded (synthetic data, or bars ingested before feeds were recorded).
            writer.write("ticker,date,open,high,low,close,volume,source,feed\n");
            service.exportCsv(resolved, from, to, splitAdjusted, writer);
            writer.flush();
        };
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bars-" + from + "-" + to + ".csv\"")
                .body(body);
    }
}
