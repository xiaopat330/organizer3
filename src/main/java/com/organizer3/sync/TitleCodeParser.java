package com.organizer3.sync;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a structured JAV title code from a folder name.
 *
 * <p>JAV codes follow the pattern {@code LABEL-NUMBER} (e.g., {@code ABP-123}, {@code PRED-456}).
 * The {@code code} field preserves the as-found representation including any variant suffix
 * (e.g., {@code ABP-123_U}). The {@code baseCode} is the normalized, suffix-free form used for
 * cross-volume matching (e.g., {@code ABP-00123}).
 *
 * <p>Some labels emit variant-release codes whose sequence portion carries a single-letter
 * prefix on the digits — e.g., Maxing's S-prefixed box sets ({@code MKBD-S119}, {@code MKD-S38}),
 * Tokyo Hot's {@code CPZ69-H005}, etc. The parser captures that optional prefix letter and
 * preserves it in both {@code code} (e.g., {@code MKBD-S119}) and {@code baseCode}
 * (e.g., {@code MKBD-S00119}). {@code seqNum} remains the pure integer portion. When the
 * prefix is absent, behavior collapses to the original {@code LABEL-00000} form.
 *
 * <p>If no recognizable pattern is found, both fields default to the raw folder name.
 */
public class TitleCodeParser {

    // Label: letter followed by up to 9 alphanumeric chars.
    // Optional single-letter seq prefix (e.g., MKBD-S119, CPZ69-H005).
    // Number: 2+ digits (unbounded upper limit — FC2PPV IDs reach 7-8+ digits).
    private static final Pattern CODE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9]{0,9})-([A-Za-z]?)(\\d{2,})");

    // A suffix immediately after the number, like _U or _4K
    private static final Pattern SUFFIX = Pattern.compile(
            "^(_[A-Za-z0-9]+)");

    // A parenthesised group, e.g. "(IESP-409)". The canonical code lives here per the
    // library's "<Actress> - <Description> (CODE)" folder-naming convention.
    private static final Pattern PARENS = Pattern.compile("\\(([^()]*)\\)");

    public record ParsedCode(String code, String baseCode, String label, Integer seqNum) {}

    /**
     * Parses a folder name, preferring the code in a trailing parenthesised group.
     *
     * <p>The library's folder convention places the canonical code in trailing parentheses
     * — {@code <Actress> - <Description> (CODE)}. When the description itself contains a
     * code-shaped token (e.g. {@code X-File-13}, {@code R-18}, {@code U-15}), a naive
     * whole-string first-match grabs the wrong token. To avoid this we scan parenthesised
     * groups right-to-left and return the first one that yields a valid code; only if none
     * do we fall back to the whole-string match (which also preserves the raw-name default
     * for folders with no recognisable code anywhere).
     */
    public ParsedCode parse(String folderName) {
        Matcher paren = PARENS.matcher(folderName);
        // Collect parenthesised inner texts, then evaluate right-to-left (rightmost first).
        java.util.List<String> groups = new java.util.ArrayList<>();
        while (paren.find()) {
            groups.add(paren.group(1));
        }
        for (int i = groups.size() - 1; i >= 0; i--) {
            ParsedCode candidate = parseCore(groups.get(i).trim());
            if (candidate.label() != null) {
                return candidate;
            }
        }
        return parseCore(folderName);
    }

    private ParsedCode parseCore(String folderName) {
        Matcher m = CODE.matcher(folderName);
        if (!m.find()) {
            return new ParsedCode(folderName, folderName, null, null);
        }
        String label = m.group(1).toUpperCase();
        String seqPrefix = m.group(2).toUpperCase();
        String digits = m.group(3);
        String afterNumber = folderName.substring(m.end());

        String suffix = "";
        Matcher sfx = SUFFIX.matcher(afterNumber);
        if (sfx.find()) {
            suffix = sfx.group(1).toUpperCase();
        }

        int seqNum = Integer.parseInt(digits);
        String code = label + "-" + seqPrefix + digits + suffix;
        String baseCode = String.format("%s-%s%05d", label, seqPrefix, seqNum);
        return new ParsedCode(code, baseCode, label, seqNum);
    }
}
