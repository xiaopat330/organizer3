package com.organizer3.avstars.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured metadata from AV video filenames.
 *
 * <p>The AV Stars library has no single naming convention — filenames come from many
 * sources (studio RSS feeds, torrent sites, personal renames). The parser is intentionally
 * conservative: when it can't extract a field with reasonable confidence it leaves it null
 * rather than polluting the DB with guesses.
 *
 * <p>Patterns observed in the real library:
 * <pre>
 *   [Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4
 *   !DorcelClub - 2015.12.30 Gets A Hard DP Action 2160p-h265.mkv
 *   2008.07 [Vouyer Media] Asa Akira (Control Freaks Scene.1) (1080p).mp4
 *   1111Customs.22.08.23.Alex.Coal.Anissa.Kate.Some.Title.2160p.mp4
 *   [AdrianaChechik.com] - 2014.09.19 - Hands Mouth Full [Gangbang, Anal, DP].mkv
 *   SomeStudio.24.03.15.First.Last.Title.Words.XXX.1080p.mp4
 * </pre>
 *
 * <p>This class is stateless and thread-safe.
 */
public final class AvFilenameParser {

    // ── studio ──────────────────────────────────────────────────────────────

    /** Leading [Studio Name] bracket — most common from scraped downloads. */
    private static final Pattern STUDIO_BRACKET = Pattern.compile(
            "^\\[([^\\]]{2,40})]");

    /** Leading !StudioName marker (e.g. "!DorcelClub"). Bang-form names are CamelCase — no spaces. */
    private static final Pattern STUDIO_BANG = Pattern.compile(
            "^!([A-Za-z0-9][A-Za-z0-9_-]{1,38})(?=[ .\\-_(\\[])");

    /**
     * Dot-form studio at start: Studio.YY.MM.DD or Studio.YYYY.MM.DD.
     * Captures the part before the first date-like segment.
     */
    private static final Pattern STUDIO_DOT_FORM = Pattern.compile(
            "^([A-Za-z0-9]{3,30})\\.(?:\\d{2}|\\d{4})\\.\\d{2}\\.\\d{2}\\.");

    // ── release date ────────────────────────────────────────────────────────

    /** Full ISO-like date: 2014.09.19 or 2014-09-19 or 2014/09/19. */
    private static final Pattern DATE_FULL = Pattern.compile(
            "\\b(20\\d{2})[.\\-/](0[1-9]|1[0-2])[.\\-/](0[1-9]|[12]\\d|3[01])\\b");

    /** Two-digit year: 22.08.23 or 22-08-23 (ambiguous — used only when preceded by a dot-studio). */
    private static final Pattern DATE_TWO_DIGIT = Pattern.compile(
            "(?<=\\.)([12]\\d)\\.(0[1-9]|1[0-2])\\.(0[1-9]|[12]\\d|3[01])(?=\\.)");

    /** Year-only: (2019) or 2019 surrounded by word boundaries. */
    private static final Pattern DATE_YEAR_ONLY = Pattern.compile(
            "(?:^|[^\\d])(20[012]\\d)(?:[^\\d]|$)");

    // ── resolution ──────────────────────────────────────────────────────────

    private static final Pattern RESOLUTION = Pattern.compile(
            "\\b(4[Kk]|2160p|1080p|720p|480p|576p)\\b",
            Pattern.CASE_INSENSITIVE);

    // ── codec ────────────────────────────────────────────────────────────────

    private static final Pattern CODEC = Pattern.compile(
            "\\b(HEVC|[Hh]265|[Xx]265|[Hh]264|[Xx]264|AVC|WEB-DL|WEBRip)\\b");

    // ── trailing tag block ───────────────────────────────────────────────────

    /**
     * Trailing [tag1, tag2, tag3] block — must be the last bracket group and
     * must contain at least one comma (distinguishing it from a studio/encoder bracket).
     */
    private static final Pattern TAG_BLOCK = Pattern.compile(
            "\\[([^\\]]{5,200})]\\s*$");

    // ── leading sort-trick characters ────────────────────────────────────────

    private static final Pattern LEADING_SORT_CHARS = Pattern.compile("^[!@#$%^&*+~`]+");

    // ── Parsed result ────────────────────────────────────────────────────────

    public record ParsedFilename(
            String studio,
            String releaseDate,   // normalised to YYYY-MM-DD where possible; "YYYY" for year-only
            String resolution,
            String codec,
            List<String> tags
    ) {}

    /**
     * Parses {@code filename} (just the filename, no path component, no extension).
     * All fields are nullable; the result is never null itself.
     */
    public ParsedFilename parse(String filename) {
        if (filename == null || filename.isBlank()) {
            return new ParsedFilename(null, null, null, null, List.of());
        }

        // Strip extension if present
        String name = filename;
        int dotPos = name.lastIndexOf('.');
        if (dotPos > 0) {
            String ext = name.substring(dotPos + 1).toLowerCase();
            if (ext.length() <= 4) name = name.substring(0, dotPos);
        }

        // Extract studio before stripping sort chars — STUDIO_BANG matches the leading '!'
        String studio = extractStudio(name);

        // Strip leading sort-trick characters for subsequent field extraction
        name = LEADING_SORT_CHARS.matcher(name).replaceFirst("");
        String releaseDate = extractDate(name);
        String resolution = extractResolution(name);
        String codec = extractCodec(name);
        List<String> tags = extractTags(name);

        return new ParsedFilename(studio, releaseDate, resolution, codec, tags);
    }

    // ── extraction helpers ───────────────────────────────────────────────────

    private String extractStudio(String name) {
        Matcher m;

        m = STUDIO_BRACKET.matcher(name);
        if (m.find()) return normaliseStudio(m.group(1));

        m = STUDIO_BANG.matcher(name);
        if (m.find()) return normaliseStudio(m.group(1));

        m = STUDIO_DOT_FORM.matcher(name);
        if (m.find()) return normaliseStudio(m.group(1));

        return null;
    }

    private static String normaliseStudio(String raw) {
        // Reject obvious non-studio matches (encoder tags, person names with dots, etc.)
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        // Reject if it looks like a person name bracket "(Actress Name)"
        if (trimmed.startsWith("(") || trimmed.endsWith(")")) return null;
        return trimmed;
    }

    private String extractDate(String name) {
        Matcher m;

        // Prefer full YYYY.MM.DD or YYYY-MM-DD
        m = DATE_FULL.matcher(name);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
        }

        // Two-digit year (dot-form filenames only)
        m = DATE_TWO_DIGIT.matcher(name);
        if (m.find()) {
            int yy = Integer.parseInt(m.group(1));
            // Heuristic: 00–30 → 20xx, 31–99 → 19xx (virtually no 19xx content here)
            String fullYear = yy <= 30 ? "20" + m.group(1) : "19" + m.group(1);
            return fullYear + "-" + m.group(2) + "-" + m.group(3);
        }

        // Year-only fallback
        m = DATE_YEAR_ONLY.matcher(name);
        if (m.find()) return m.group(1);

        return null;
    }

    private String extractResolution(String name) {
        Matcher m = RESOLUTION.matcher(name);
        if (!m.find()) return null;
        // Normalise 4K/4k → 2160p
        String r = m.group(1);
        return r.equalsIgnoreCase("4K") ? "2160p" : r.toLowerCase();
    }

    private String extractCodec(String name) {
        Matcher m = CODEC.matcher(name);
        if (!m.find()) return null;
        String c = m.group(1);
        // Normalise aliases
        return switch (c.toLowerCase()) {
            case "hevc", "h265", "x265" -> "h265";
            case "h264", "x264", "avc"  -> "h264";
            default -> c.toLowerCase();
        };
    }

    private List<String> extractTags(String name) {
        Matcher m = TAG_BLOCK.matcher(name);
        if (!m.find()) return List.of();
        String raw = m.group(1);
        // Only treat as a tag block if it contains commas (distinguishes from encoder groups)
        if (!raw.contains(",")) return List.of();

        List<String> tags = new ArrayList<>();
        for (String tag : raw.split(",")) {
            String t = tag.trim();
            if (!t.isEmpty()) tags.add(t);
        }
        return List.copyOf(tags);
    }
}
