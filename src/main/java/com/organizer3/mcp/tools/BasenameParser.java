package com.organizer3.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a JAV title folder basename into its constituent parts: cast list, description,
 * code, and trailing tag.
 *
 * <p>The expected format is:
 * <pre>  Cast1, Cast2 - Description (CODE) [TrailingTag]</pre>
 *
 * <p>Algorithm (locked-in):
 * <ol>
 *   <li>Scan basename right-to-left for {@code (...)} groups. The last group matching
 *       {@code ^[A-Z0-9]+-[0-9]+$} (case-insensitive, loose) is the code anchor.
 *       Anything after that matched {@code (CODE)} is the trailing tag.</li>
 *   <li>If no {@code (CODE)} found → emit {@code unparseable-basename}, return with {@code code=null}.</li>
 *   <li>If multiple candidates with no clear last match → emit {@code ambiguous-code}, pick rightmost.</li>
 *   <li>Strip code + trailing tag. Remaining head = text before {@code " (CODE)"}.</li>
 *   <li>Find last {@code " - "} (space-hyphen-space) in head. Before = cast list; after = description.</li>
 *   <li>Tokenize cast list on {@code ", "}, {@code ","}, {@code " + "}, {@code "+"}, {@code " & "}, {@code "&"}.</li>
 *   <li>Non-standard separators ({@code +}, {@code &}) surface a {@code non-standard-separator} warning.</li>
 * </ol>
 *
 * <p>Shared by {@link FindFsOnlyTitlesTool} (code extraction only) and
 * {@link FindMultiActressFolderDriftTool} (full parse).
 */
public final class BasenameParser {

    /** Loose pattern: uppercase letters/digits, hyphen, digits. Groups: (LABEL)(-)(DIGITS). */
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "\\(([A-Za-z0-9]+-[0-9]+)\\)");

    private BasenameParser() {}

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Parse result. {@code code} is null when the basename is unparseable.
     * {@code warnings} contains any non-fatal issues detected during parsing.
     */
    public record ParseResult(
            String code,
            String description,
            String trailingTag,
            List<String> castTokens,
            List<String> warnings
    ) {
        public boolean isParseable() { return code != null; }
    }

    /**
     * Extract the code only — lightweight variant for callers that don't need the full cast parse.
     *
     * @param basename folder name (just the last path segment)
     * @return the extracted code string, or {@code null} if unparseable
     */
    public static String extractCode(String basename) {
        if (basename == null || basename.isBlank()) return null;
        List<int[]> codeSpans = findCodeSpans(basename);
        if (codeSpans.isEmpty()) return null;
        int[] last = codeSpans.get(codeSpans.size() - 1);
        // Extract just the code (without surrounding parens)
        return basename.substring(last[0] + 1, last[1] - 1); // strip ( and )
    }

    /**
     * Full parse of a folder basename.
     *
     * @param basename folder name (just the last path segment)
     * @return parse result; {@code code} is null if unparseable
     */
    public static ParseResult parse(String basename) {
        if (basename == null || basename.isBlank()) {
            return new ParseResult(null, null, null, List.of(), List.of("unparseable-basename"));
        }

        List<int[]> codeSpans = findCodeSpans(basename);
        List<String> warnings = new ArrayList<>();

        if (codeSpans.isEmpty()) {
            return new ParseResult(null, null, null, List.of(), List.of("unparseable-basename"));
        }

        // Pick the rightmost code span
        int[] codeSpan = codeSpans.get(codeSpans.size() - 1);
        if (codeSpans.size() > 1) {
            warnings.add("ambiguous-code");
        }

        String code = basename.substring(codeSpan[0] + 1, codeSpan[1] - 1); // strip parens

        // Everything after the (CODE) is the trailing tag
        String trailingTag = basename.substring(codeSpan[1]);
        if (trailingTag.isBlank()) {
            trailingTag = null;
        }

        // Head = text before " (CODE)"
        String head = basename.substring(0, codeSpan[0]).stripTrailing();
        // Also strip a leading space if present
        if (!head.isEmpty() && head.charAt(head.length() - 1) == ' ') {
            head = head.stripTrailing();
        }

        // Find last " - " to split cast from description
        String castPart;
        String description = null;
        int sepIdx = head.lastIndexOf(" - ");
        if (sepIdx >= 0) {
            castPart    = head.substring(0, sepIdx);
            description = head.substring(sepIdx + 3); // skip " - "
        } else {
            castPart = head;
        }

        // Tokenize cast list
        List<String> castTokens = tokenizeCast(castPart, warnings);

        return new ParseResult(code, description, trailingTag, castTokens, List.copyOf(warnings));
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns list of [start, end) spans (inclusive of parens) for each
     * {@code (CODE-NNN)} match in the basename, in left-to-right order.
     * Only spans where the inner content matches the loose code pattern are included.
     */
    private static List<int[]> findCodeSpans(String basename) {
        Matcher m = CODE_PATTERN.matcher(basename);
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
        }
        return spans;
    }

    /**
     * Tokenize the cast part of a basename.
     * Standard separator: {@code ", "} or {@code ","}.
     * Non-standard: {@code " + "}, {@code "+"}, {@code " & "}, {@code "&"}.
     * Non-standard separators cause a {@code non-standard-separator} warning to be appended.
     */
    static List<String> tokenizeCast(String castPart, List<String> warnings) {
        if (castPart == null || castPart.isBlank()) return List.of();

        // Detect non-standard separators first
        boolean hasNonStandard = castPart.contains(" & ") || castPart.contains("&")
                || castPart.contains(" + ") || castPart.contains("+");

        // Split the string using a regex that matches all supported separators at once.
        // Order matters: multi-char sequences first so they don't get partially matched.
        // Supported: ", "  ","  " + "  "+"  " & "  "&"
        // We use a single regex alternation to avoid sentinel pollution.
        List<String> tokens = new ArrayList<>();
        // Split on: (space)(& or +)(space) or bare (& or +) or (,)(optional space)
        String[] parts = castPart.split("\\s*(?:&|\\+)\\s*|,\\s*", -1);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }

        if (hasNonStandard) {
            warnings.add("non-standard-separator");
        }

        return tokens;
    }
}
