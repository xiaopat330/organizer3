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
    // Number: 2-6 digits.
    private static final Pattern CODE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9]{0,9})-([A-Za-z]?)(\\d{2,6})");

    // A suffix immediately after the number, like _U or _4K
    private static final Pattern SUFFIX = Pattern.compile(
            "^(_[A-Za-z0-9]+)");

    public record ParsedCode(String code, String baseCode, String label, Integer seqNum) {}

    public ParsedCode parse(String folderName) {
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
