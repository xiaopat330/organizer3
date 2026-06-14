package com.organizer3.javdb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical product-code normalization for matching against javdb.
 *
 * <p>javdb represents codes with the maker's minimal zero-padding (e.g. {@code SEND-02},
 * {@code SCOP-515}) and never pads to a fixed width. Our DB, by contrast, stores both a
 * 5-digit zero-padded {@code base_code} ({@code SEND-00002}) and a minimally-padded
 * {@code code} ({@code SEND-002}). Exact-string comparison between these representations
 * produces false "no-match" results.
 *
 * <p>{@link #normalizeForMatch} collapses every leading-zero variant of the same integer
 * to a single key, so any representation of one title compares equal regardless of padding.
 * Because leading-zero variants always denote the same integer (and therefore the same
 * title), normalization can never create a false match between distinct titles.
 */
public final class JavdbCode {

    private JavdbCode() {}

    // Mirrors TitleCodeParser's capture groups so the optional seq-prefix letter is preserved:
    //   group 1 = label, group 2 = optional seq-prefix letter, group 3 = digits (leading zeros stripped).
    // A trailing ".*" absorbs variant suffixes like "_4K" / "_U".
    private static final Pattern CODE = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9]{0,9})-([A-Za-z]?)0*(\\d+).*$");

    /** Canonical code for javdb matching. Strips leading zeros from the numeric part so that
     *  every leading-zero variant of the same integer collapses to one key:
     *    "SEND-00002" / "SEND-002" / "SEND-02" → "SEND-2"
     *    "SCOP-00515"                           → "SCOP-515"
     *    "MKBD-S00119"                          → "MKBD-S119"  (preserve the seq-prefix letter)
     *  Variant suffixes are ignored ("SONE-038_4K" → "SONE-38"). Unparseable input is returned
     *  trimmed and unchanged. */
    public static String normalizeForMatch(String code) {
        if (code == null) return null;
        Matcher m = CODE.matcher(code);
        if (!m.matches()) {
            return code.trim();
        }
        String label = m.group(1).toUpperCase();
        String seqPrefix = m.group(2).toUpperCase();
        String digits = m.group(3); // "0*" already consumed leading zeros; "\\d+" leaves a lone "0" for all-zeros input
        return label + "-" + seqPrefix + digits;
    }
}
