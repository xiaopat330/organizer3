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
 * <p>If no recognizable pattern is found, both fields default to the raw folder name.
 */
public class TitleCodeParser {

    // Label: letter followed by up to 9 alphanumeric chars. Number: 2-6 digits.
    private static final Pattern CODE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9]{0,9})-(\\d{2,6})");

    // A suffix immediately after the number, like _U or _4K
    private static final Pattern SUFFIX = Pattern.compile(
            "^(_[A-Za-z0-9]+)");

    public record ParsedCode(String code, String baseCode) {}

    public ParsedCode parse(String folderName) {
        Matcher m = CODE.matcher(folderName);
        if (!m.find()) {
            return new ParsedCode(folderName, folderName);
        }
        String label = m.group(1).toUpperCase();
        String digits = m.group(2);
        String afterNumber = folderName.substring(m.end());

        String suffix = "";
        Matcher sfx = SUFFIX.matcher(afterNumber);
        if (sfx.find()) {
            suffix = sfx.group(1).toUpperCase();
        }

        String code = label + "-" + digits + suffix;
        String baseCode = String.format("%s-%05d", label, Integer.parseInt(digits));
        return new ParsedCode(code, baseCode);
    }
}
