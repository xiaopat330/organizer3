package com.organizer3.sync;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses user-typed product-number fragments into a {@code (labelPrefix, seqPrefix)} pair
 * suitable for a starts-with search against the {@code titles.label} and {@code titles.seq_num}
 * columns.
 *
 * <p>The parser is intentionally lenient: leading zeros on the number portion are stripped
 * so that {@code SNIS-1}, {@code SNIS-01}, {@code SNIS-001} and {@code SNIS-00001} all resolve
 * to the same {@code ("SNIS", "1")} pair. Whitespace is removed, letters are uppercased, and
 * the dash separator is optional — {@code SNIS1}, {@code SNIS01}, {@code SNIS001} all resolve
 * identically.
 *
 * <p>Input shapes recognized:
 * <ul>
 *   <li>{@code ""} → empty query
 *   <li>{@code "SN"}, {@code "SNIS"} → label prefix only, no seq
 *   <li>{@code "SNIS-"} → label "SNIS", no seq
 *   <li>{@code "SNIS-1"}, {@code "SNIS1"}, {@code "SNIS001"} → label "SNIS", seq "1"
 *   <li>{@code "FC2PPV-01"} → label "FC2PPV", seq "1" (dash disambiguates labels containing digits)
 * </ul>
 */
public class TitleCodeQuery {

    /** Label followed by optional dash and digits, e.g. {@code SNIS-001} or {@code SNIS001}. */
    private static final Pattern LETTERS_THEN_DIGITS = Pattern.compile("^([A-Z]+)-?(\\d*)$");

    /** Any label (may contain digits) followed by dash and digits, e.g. {@code FC2PPV-01}. */
    private static final Pattern WITH_DASH = Pattern.compile("^([A-Z][A-Z0-9]*)-(\\d*)$");

    public record ParsedQuery(String labelPrefix, String seqPrefix) {
        public boolean isEmpty() {
            return labelPrefix.isEmpty() && seqPrefix.isEmpty();
        }
    }

    /**
     * Parses {@code rawInput} into a normalized {@link ParsedQuery}. Returns an empty query
     * when {@code rawInput} is null, blank, or contains only punctuation.
     */
    public static ParsedQuery parse(String rawInput) {
        if (rawInput == null) return new ParsedQuery("", "");
        String input = rawInput.trim().toUpperCase().replaceAll("\\s+", "");
        if (input.isEmpty()) return new ParsedQuery("", "");

        // Prefer the unambiguous "LABEL-NUM" form when a dash is present, so labels that
        // legitimately contain digits (FC2PPV-style) are handled correctly.
        Matcher m = WITH_DASH.matcher(input);
        if (m.matches()) {
            return new ParsedQuery(m.group(1), stripLeadingZeros(m.group(2)));
        }

        // Otherwise split on the letter→digit boundary (SNIS001-style). This only succeeds
        // when the label portion is pure letters, which covers the overwhelming majority of
        // JAV labels.
        m = LETTERS_THEN_DIGITS.matcher(input);
        if (m.matches()) {
            return new ParsedQuery(m.group(1), stripLeadingZeros(m.group(2)));
        }

        // Fallback: treat the whole input as a label prefix. Strip a trailing dash so that
        // "SNIS-" behaves like "SNIS".
        return new ParsedQuery(input.replaceAll("-+$", ""), "");
    }

    private static String stripLeadingZeros(String digits) {
        if (digits == null || digits.isEmpty()) return "";
        String stripped = digits.replaceFirst("^0+", "");
        return stripped;  // "0000" → "" which is fine; empty seq means "any seq"
    }
}
