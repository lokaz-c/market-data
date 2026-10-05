package com.lokaz.marketdata.sql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;

/**
 * Loads SQL statements from {@code src/main/resources/sql}. Keeping SQL in .sql files means the exact
 * statements the service runs can be read, reviewed and run with EXPLAIN in psql (see scripts/explain.sh).
 */
public final class SqlFiles {

    private SqlFiles() {
    }

    public static String load(String name) {
        var resource = new ClassPathResource("sql/" + name);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing SQL file sql/" + name, e);
        }
    }
}
