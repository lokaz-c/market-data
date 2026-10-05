package com.lokaz.marketdata.api;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.jspecify.annotations.Nullable;

final class Decimals {

    private Decimals() {
    }

    /** NUMERIC(18,6) comes back as 185.640000; send 185.64 (and 100, not 1E+2). */
    static @Nullable BigDecimal trim(@Nullable BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    static @Nullable BigDecimal get(ResultSet rs, String column) throws SQLException {
        return trim(rs.getBigDecimal(column));
    }
}
