package com.lokaz.marketdata.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/** Checks X-API-Key values against the configured SHA-256 digests in constant time. */
@Component
public class ApiKeys {

    private final List<byte[]> digests;

    public ApiKeys(ApiProperties properties) {
        this.digests = properties.keySha256().stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> HexFormat.of().parseHex(s.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public boolean isValid(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank() || digests.isEmpty()) {
            return false;
        }
        byte[] presented = sha256(presentedKey.strip());
        boolean match = false;
        for (byte[] digest : digests) {
            match |= MessageDigest.isEqual(digest, presented); // no early exit
        }
        return match;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
