package com.organizer3.translation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * Normalization helpers for translation cache keys.
 *
 * <p>Normalization rules per §5.5.2:
 * <ol>
 *   <li>Trim leading/trailing whitespace.</li>
 *   <li>Apply NFKC normalization (half-width katakana, full-width digits, etc.).</li>
 *   <li>Collapse internal runs of whitespace to a single space.</li>
 * </ol>
 *
 * <p>The SHA-256 hash of the normalised form is the cache index column. Using a hash keeps
 * the index bounded even for multi-KB inputs (biography paragraphs).
 */
public final class TranslationNormalization {

    private TranslationNormalization() {}

    /**
     * Normalise a source string as described in §5.5.2.
     * Both the write path and read path must call this before computing the cache key.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        String nfkc = Normalizer.normalize(trimmed, Normalizer.Form.NFKC);
        // Collapse internal whitespace runs to a single space
        return nfkc.replaceAll("\\s+", " ");
    }

    /**
     * SHA-256 hex digest of the normalised form. Always call {@link #normalize} first,
     * or use {@link #hashOf(String)} which does both.
     */
    public static String sha256Hex(String normalised) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Convenience: normalise the input and return its SHA-256 hash.
     * This is the single entry point callers should use to derive a cache key.
     */
    public static String hashOf(String raw) {
        return sha256Hex(normalize(raw));
    }
}
