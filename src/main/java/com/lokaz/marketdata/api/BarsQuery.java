package com.lokaz.marketdata.api;

import java.time.LocalDate;

import org.jspecify.annotations.Nullable;

/**
 * The parameters of one /v1/bars request. Either a keyset page ({@code from}, {@code to}, {@code after},
 * {@code limit}) or the latest {@code last} bars on or before {@code to}.
 */
record BarsQuery(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable LocalDate after, int limit,
        @Nullable Integer last, boolean splitAdjusted) {

    static final int DEFAULT_LIMIT = 500;

    /** Rejects combinations single-parameter validation cannot see: {@code last} is a page of its own. */
    static BarsQuery of(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable LocalDate after,
            @Nullable Integer limit, @Nullable Integer last, boolean splitAdjusted) {
        if (last != null && (from != null || after != null || limit != null)) {
            throw new ApiExceptions.BadRequest("last cannot be combined with from, after or limit.");
        }
        return new BarsQuery(from, to, after, limit != null ? limit : DEFAULT_LIMIT, last, splitAdjusted);
    }

    String adjustment() {
        return splitAdjusted ? "split" : "raw";
    }
}
