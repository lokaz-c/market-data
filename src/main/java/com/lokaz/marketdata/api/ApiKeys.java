package com.lokaz.marketdata.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checks X-API-Key values against configured SHA-256 digests in constant time and returns the key's scopes. Only
 * digests are configured, so the keys themselves never sit in config or env.
 *
 * <ul>
 * <li>{@code API_KEYS}: entries {@code <sha256 hex>:<scope>[,<scope>...]}, separated by semicolons. Scopes are
 * {@code rate-limit}, {@code alpaca-data} and {@code export}.</li>
 * <li>{@code API_KEY_SHA256}: the original setting, comma-separated digests. These keys keep every scope, so
 * existing deployments behave as before.</li>
 * </ul>
 *
 * A malformed entry, an unknown scope or a digest configured twice stops startup: a key with the wrong scopes is
 * worse than a service that does not start.
 */
@Component
public class ApiKeys {

    private static final Logger log = LoggerFactory.getLogger(ApiKeys.class);
    private static final int SHA256_HEX_LENGTH = 64;
    private static final Set<Scope> EVERY_SCOPE = Collections.unmodifiableSet(EnumSet.allOf(Scope.class));

    private record Entry(byte[] digest, Set<Scope> scopes) {
    }

    private final List<Entry> entries;

    public ApiKeys(ApiProperties properties) {
        var parsed = new ArrayList<Entry>();
        int legacy = 0;
        for (String digest : properties.keySha256()) {
            if (!digest.isBlank()) {
                parsed.add(new Entry(parseDigest(digest, "API_KEY_SHA256"), EVERY_SCOPE));
                legacy++;
            }
        }
        for (String entry : properties.keys().split(";")) {
            if (!entry.isBlank()) {
                parsed.add(parseEntry(entry.strip()));
            }
        }
        for (int i = 0; i < parsed.size(); i++) {
            for (int j = i + 1; j < parsed.size(); j++) {
                if (Arrays.equals(parsed.get(i).digest(), parsed.get(j).digest())) {
                    throw new IllegalArgumentException("The same key digest is configured twice in API_KEYS and "
                            + "API_KEY_SHA256; give each key one entry with all of its scopes.");
                }
            }
        }
        this.entries = List.copyOf(parsed);
        if (legacy > 0) {
            log.info("{} API key(s) from API_KEY_SHA256 have every scope; list keys in API_KEYS to limit them", legacy);
        }
    }

    /** @return the key's scopes, or empty if the key is missing, blank or not configured */
    public Optional<Set<Scope>> scopes(@Nullable String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank() || entries.isEmpty()) {
            return Optional.empty();
        }
        byte[] presented = sha256(presentedKey.strip());
        Set<Scope> match = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.digest(), presented)) { // constant time; no early exit from the loop
                match = entry.scopes();
            }
        }
        return Optional.ofNullable(match);
    }

    private static Entry parseEntry(String entry) {
        int colon = entry.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("API_KEYS entries are <sha256 hex>:<scope>[,<scope>...]; an entry "
                    + "has no scopes. Scopes: " + Scope.names());
        }
        Set<Scope> scopes;
        try {
            scopes = Scope.parseAll(entry.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("API_KEYS: " + e.getMessage(), e);
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("API_KEYS: an entry lists no scopes. Scopes: " + Scope.names());
        }
        return new Entry(parseDigest(entry.substring(0, colon), "API_KEYS"), Collections.unmodifiableSet(scopes));
    }

    private static byte[] parseDigest(String hex, String setting) {
        String digest = hex.strip().toLowerCase(Locale.ROOT);
        if (digest.length() != SHA256_HEX_LENGTH || !digest.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new IllegalArgumentException(setting + " holds SHA-256 digests of keys: 64 hex characters each, "
                    + "got " + digest.length() + " characters. Digest a key with: printf %s \"$KEY\" | shasum -a 256");
        }
        return HexFormat.of().parseHex(digest);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
