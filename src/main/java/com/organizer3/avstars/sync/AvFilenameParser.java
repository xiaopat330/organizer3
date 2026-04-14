package com.organizer3.avstars.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured metadata from AV Stars video filenames.
 *
 * <p>The library has no single naming convention. This parser was designed against
 * the actual 14,694-file corpus on qnap_av (//qnap2/AV/stars) and handles the
 * dominant patterns in rough order of prevalence:
 *
 * <ol>
 *   <li><b>Actress – [Studio.com] – [YYYY] – Title-h265.mkv</b> (~2,600 files)
 *       <br>e.g. {@code Eliza Ibarra - [Blacked.com] - [2021] - Bye Boyfriend-h265.mkv}
 *   <li><b>[YY]Studio.Title.YYYY-MM-DD-h265.mkv</b> (~884 files)
 *       <br>e.g. {@code [15]Brazzers.Couch Cooch.2015-08-11-h265.mkv}
 *   <li><b>lowercase.YY.MM.DD.actress.title.resolution-h265.mkv</b> (~1,042 files)
 *       <br>e.g. {@code academypov.21.01.28.tiffany.tatum.study.buddy.4k-h265.mkv}
 *   <li><b>CamelCase.YY.MM.DD.actress.title-h265.mkv</b> (~497 files)
 *       <br>e.g. {@code ALSScan.17.08.05.Charity.Crawford.Romp.About.mp4}
 *   <li><b>[Studio.com] – YYYY.MM.DD – Title [tags]-h265.mkv</b> (~1,255 files)
 *       <br>e.g. {@code [AdrianaChechik.com] - 2014.09.19 - Hands Mouth Full [Gangbang, Anal, DP]-h265.mkv}
 *   <li><b>!Studio – YYYY.MM.DD Title resolution-h265.mkv</b> (~33 files)
 *       <br>e.g. {@code !DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p-h265.mkv}
 * </ol>
 *
 * <p>Processing order: strip extension → extract codec suffix → extract resolution suffix
 * → extract tags (now at end) → extract studio → extract date.
 *
 * <p>This class is stateless and thread-safe.
 */
public final class AvFilenameParser {

    // ── Extension whitelist ───────────────────────────────────────────────────

    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mkv", "mp4", "avi", "wmv", "mov", "ts");

    // ── Codec suffix patterns (applied to name after extension stripped) ──────

    /**
     * Resolution immediately before codec: ".4k-h265", " 2160p-h265", etc.
     * Group 1 = resolution token, group 2 = codec.
     */
    private static final Pattern RES_THEN_CODEC_SUFFIX = Pattern.compile(
            "[-_. ](4[Kk]|2160p|1080p|720p|480p|576p)-(h265|h264)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Codec immediately before resolution: "-h265.4K", "-h265.4k", "-h265.2160p".
     * Group 1 = codec, group 2 = resolution.
     */
    private static final Pattern CODEC_THEN_RES_SUFFIX = Pattern.compile(
            "-(h265|h264)\\.(4[Kk]|2160p)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Release-group dot-form: "1080p.HEVC.x265.PRT" or "XXX.2160p.HEVC.x265.PRT".
     * Group 1 = resolution, remaining = codec (always h265).
     */
    private static final Pattern RELEASE_GROUP_SUFFIX = Pattern.compile(
            "(?:\\.XXX)?\\.(4[Kk]|2160p|1080p|720p|480p)\\.HEVC\\.x265(?:\\.[A-Z0-9]{1,5})*$",
            Pattern.CASE_INSENSITIVE);

    /** Plain codec suffix: "-h265", ".x265", ".x264", "_h264", "_h265", " HEVC", " AVC". */
    private static final Pattern PLAIN_CODEC_SUFFIX = Pattern.compile(
            "[-_. ](h265|h264|x265|x264|hevc|avc)(?:-[A-Za-z0-9]+)?$",
            Pattern.CASE_INSENSITIVE);

    // ── Resolution (after codec suffix stripped, or in older files with no codec) ──

    /** Dot-suffix resolution with no codec: ".4K", ".2160p", ".1080p". */
    private static final Pattern RESOLUTION_DOT_SUFFIX = Pattern.compile(
            "\\.(4[Kk]|2160p|1080p|720p|480p|576p)$",
            Pattern.CASE_INSENSITIVE);

    /** Word-boundary resolution anywhere in name (fallback). */
    private static final Pattern RESOLUTION_WORD = Pattern.compile(
            "\\b(4[Kk]|2160p|1080p|720p|480p|576p)\\b",
            Pattern.CASE_INSENSITIVE);

    // ── Studio patterns ───────────────────────────────────────────────────────

    /**
     * Pattern 1 – "Actress Name - [Studio.com] - ..." or "Actress Name - [Studio] - ...".
     * The first bracket group after the first " - [" is the studio.
     */
    private static final Pattern STUDIO_ACTRESS_BRACKET = Pattern.compile(
            "^.+? - \\[([^\\]]{2,60})\\]");

    /**
     * Pattern 2 – "[YY]Studio.Title..." where [YY] is a 1-2 digit sort prefix.
     * Captures the CamelCase studio name between ']' and the first '.'.
     */
    private static final Pattern STUDIO_YEAR_PREFIX = Pattern.compile(
            "^\\[\\d{1,2}\\]([A-Za-z0-9!][^.]{0,50})\\.");

    /**
     * Pattern 3 – "[Studio.com] - ..." or "[Studio] - ..." leading bracket (not a
     * year-prefix bracket). Disambiguated from pattern 2 by checking that the bracket
     * content has no commas and is not purely numeric.
     */
    private static final Pattern STUDIO_LEADING_BRACKET = Pattern.compile(
            "^\\[([^\\]\\d][^\\]]{1,60})\\]");

    /**
     * Pattern 4 – "!Studio - ..." bang prefix.
     * Bang-form names are CamelCase (no spaces).
     */
    private static final Pattern STUDIO_BANG = Pattern.compile(
            "^!([A-Za-z0-9][A-Za-z0-9_-]{1,38})(?=[ .\\-_(\\[])");

    /**
     * Pattern 5 – dot-form: "studio.YY.MM.DD." or "Studio.YYYY.MM.DD."
     * Studio is everything before the first date-like segment.
     */
    private static final Pattern STUDIO_DOT_FORM = Pattern.compile(
            "^([A-Za-z0-9!][A-Za-z0-9_!]*)\\.(?:\\d{4}|\\d{2})\\.\\d{2}\\.\\d{2}\\.");

    // ── Date patterns ─────────────────────────────────────────────────────────

    /** [YYYY] bracket — year only, common in "Actress - [Studio] - [2021] - Title". */
    private static final Pattern DATE_BRACKET_YEAR = Pattern.compile(
            "\\[(20\\d{2})\\]");

    /** [YY.MM.DD] bracket — two-digit year inside brackets. */
    private static final Pattern DATE_BRACKET_TWO_DIGIT = Pattern.compile(
            "\\[([12]\\d)\\.(0[1-9]|1[0-2])\\.(0[1-9]|[12]\\d|3[01])\\]");

    /** Full YYYY.MM.DD or YYYY-MM-DD or YYYY/MM/DD. */
    private static final Pattern DATE_FULL = Pattern.compile(
            "\\b(20\\d{2})[.\\-/](0[1-9]|1[0-2])[.\\-/](0[1-9]|[12]\\d|3[01])\\b");

    /**
     * Two-digit YY.MM.DD in dot-form filenames (must be preceded by a '.').
     * Only used when the name has the dot-form studio pattern.
     */
    private static final Pattern DATE_TWO_DIGIT_DOT = Pattern.compile(
            "(?<=\\.)([12]\\d)\\.(0[1-9]|1[0-2])\\.(0[1-9]|[12]\\d|3[01])(?=\\.)");

    /** Year-only fallback: bare 20xx not adjacent to other digits. */
    private static final Pattern DATE_YEAR_ONLY = Pattern.compile(
            "(?:^|[^\\d])(20[012]\\d)(?:[^\\d]|$)");

    // ── Tag block ─────────────────────────────────────────────────────────────

    /**
     * Trailing comma-separated tag block at end of the name string (after codec
     * suffix has been stripped, so the block is now genuinely at end of string).
     * Must contain at least one comma to distinguish from encoder/studio brackets.
     */
    private static final Pattern TAG_BLOCK = Pattern.compile(
            "\\[([^\\]]{5,200})]\\s*$");

    // ── Leading sort characters ───────────────────────────────────────────────

    private static final Pattern LEADING_SORT_CHARS = Pattern.compile(
            "^[!@#$%^&*+~`\u0444\u041d\u0410-\u042f\u0430-\u044f]+");

    // ── Result record ─────────────────────────────────────────────────────────

    public record ParsedFilename(
            String studio,
            String releaseDate,   // YYYY-MM-DD where possible; "YYYY" for year-only
            String resolution,
            String codec,
            List<String> tags
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parses {@code filename} (full filename including extension).
     * All fields are nullable; the result itself is never null.
     */
    public ParsedFilename parse(String filename) {
        if (filename == null || filename.isBlank()) {
            return new ParsedFilename(null, null, null, null, List.of());
        }

        // 1. Strip known video extension
        String name = stripVideoExtension(filename);

        // 2. Extract codec + resolution from suffix (strips them from name)
        String[] cr = extractCodecAndResolution(name);
        String codec      = cr[0];      // nullable
        String resolution = cr[1];      // nullable
        String trimmed    = cr[2];      // name with codec/res suffix removed

        // 3. If no resolution yet, look for a plain dot-suffix resolution (.4K, .1080p)
        if (resolution == null) {
            Matcher m = RESOLUTION_DOT_SUFFIX.matcher(trimmed);
            if (m.find()) {
                resolution = normaliseResolution(m.group(1));
                trimmed = trimmed.substring(0, m.start());
            }
        }

        // 4. Tags — now at end of trimmed name
        List<String> tags = extractTags(trimmed);

        // 5. Studio — operate on original name (before sort-char strip) to catch !bang
        String studio = extractStudio(name);

        // 6. Strip leading sort chars from trimmed name for date extraction
        String cleanName = LEADING_SORT_CHARS.matcher(trimmed).replaceFirst("");

        // 7. Date
        String releaseDate = extractDate(cleanName);

        // 8. Resolution fallback — word-boundary scan anywhere
        if (resolution == null) {
            Matcher m = RESOLUTION_WORD.matcher(cleanName);
            if (m.find()) resolution = normaliseResolution(m.group(1));
        }

        return new ParsedFilename(studio, releaseDate, resolution, codec, tags);
    }

    // ── Codec + resolution suffix extraction ─────────────────────────────────

    /**
     * Returns a 3-element array: [codec, resolution, strippedName].
     * Tries suffix patterns in priority order.
     */
    private static String[] extractCodecAndResolution(String name) {
        Matcher m;

        // Release-group format: "1080p.HEVC.x265.PRT"
        m = RELEASE_GROUP_SUFFIX.matcher(name);
        if (m.find()) {
            return new String[]{
                    "h265",
                    normaliseResolution(m.group(1)),
                    name.substring(0, m.start())
            };
        }

        // Resolution then codec: ".4k-h265"
        m = RES_THEN_CODEC_SUFFIX.matcher(name);
        if (m.find()) {
            return new String[]{
                    normaliseCodec(m.group(2)),
                    normaliseResolution(m.group(1)),
                    name.substring(0, m.start())
            };
        }

        // Codec then resolution: "-h265.4K"
        m = CODEC_THEN_RES_SUFFIX.matcher(name);
        if (m.find()) {
            return new String[]{
                    normaliseCodec(m.group(1)),
                    normaliseResolution(m.group(2)),
                    name.substring(0, m.start())
            };
        }

        // Plain codec only: "-h265", ".x265", etc.
        m = PLAIN_CODEC_SUFFIX.matcher(name);
        if (m.find()) {
            return new String[]{
                    normaliseCodec(m.group(1)),
                    null,
                    name.substring(0, m.start())
            };
        }

        return new String[]{null, null, name};
    }

    private static String normaliseCodec(String raw) {
        if (raw == null) return null;
        return switch (raw.toLowerCase()) {
            case "h265", "x265", "hevc" -> "h265";
            case "h264", "x264", "avc"  -> "h264";
            default -> raw.toLowerCase();
        };
    }

    private static String normaliseResolution(String raw) {
        if (raw == null) return null;
        return raw.equalsIgnoreCase("4K") || raw.equalsIgnoreCase("4k") ? "2160p" : raw.toLowerCase();
    }

    // ── Studio extraction ─────────────────────────────────────────────────────

    private static String extractStudio(String name) {
        Matcher m;

        // Pattern 1: "Actress - [Studio] - ..." (dominant pattern ~2,600 files)
        m = STUDIO_ACTRESS_BRACKET.matcher(name);
        if (m.find()) {
            String candidate = m.group(1).trim();
            // Reject if it looks like a date bracket [YYYY] or [YY.MM.DD]
            if (!candidate.matches("20\\d{2}") && !candidate.matches("[12]\\d\\.\\d{2}\\.\\d{2}")) {
                return candidate;
            }
        }

        // Pattern 4: "!Studio - ..." (check before dot-form since ! is a sort char)
        m = STUDIO_BANG.matcher(name);
        if (m.find()) return m.group(1);

        // Pattern 2: "[YY]Studio.Title" — sort-year prefix
        m = STUDIO_YEAR_PREFIX.matcher(name);
        if (m.find()) return m.group(1).trim();

        // Pattern 3: "[Studio.com] - ..." or "[Studio] - ..." leading bracket
        // Skip if it's a [YY] year-prefix (already handled above)
        m = STUDIO_LEADING_BRACKET.matcher(name);
        if (m.find()) {
            String candidate = m.group(1).trim();
            return candidate.isBlank() ? null : candidate;
        }

        // Pattern 5: "studio.YY.MM.DD." or "Studio.YYYY.MM.DD."
        m = STUDIO_DOT_FORM.matcher(name);
        if (m.find()) return m.group(1);

        return null;
    }

    // ── Date extraction ───────────────────────────────────────────────────────

    private static String extractDate(String name) {
        Matcher m;

        // Prefer [YY.MM.DD] bracket (e.g. "[21.04.10]")
        m = DATE_BRACKET_TWO_DIGIT.matcher(name);
        if (m.find()) {
            int yy = Integer.parseInt(m.group(1));
            String fullYear = yy <= 30 ? "20" + m.group(1) : "19" + m.group(1);
            return fullYear + "-" + m.group(2) + "-" + m.group(3);
        }

        // Full YYYY.MM.DD or YYYY-MM-DD
        m = DATE_FULL.matcher(name);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
        }

        // Two-digit YY.MM.DD in dot-form (must be preceded by '.')
        m = DATE_TWO_DIGIT_DOT.matcher(name);
        if (m.find()) {
            int yy = Integer.parseInt(m.group(1));
            String fullYear = yy <= 30 ? "20" + m.group(1) : "19" + m.group(1);
            return fullYear + "-" + m.group(2) + "-" + m.group(3);
        }

        // [YYYY] bracket year-only
        m = DATE_BRACKET_YEAR.matcher(name);
        if (m.find()) return m.group(1);

        // Year-only fallback
        m = DATE_YEAR_ONLY.matcher(name);
        if (m.find()) return m.group(1);

        return null;
    }

    // ── Tag extraction ────────────────────────────────────────────────────────

    private static List<String> extractTags(String name) {
        Matcher m = TAG_BLOCK.matcher(name);
        if (!m.find()) return List.of();
        String raw = m.group(1);
        if (!raw.contains(",")) return List.of();
        List<String> tags = new ArrayList<>();
        for (String tag : raw.split(",")) {
            String t = tag.trim();
            if (!t.isEmpty()) tags.add(t);
        }
        return List.copyOf(tags);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String stripVideoExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) return filename;
        String ext = filename.substring(dot + 1).toLowerCase();
        return VIDEO_EXTENSIONS.contains(ext) ? filename.substring(0, dot) : filename;
    }
}
