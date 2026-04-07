package com.organizer3.sync.scanner;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Actress;
import com.organizer3.sync.TitleCodeParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for scanner implementations.
 */
public final class ScannerSupport {

    private ScannerSupport() {}

    // Matches "Actress Name (CODE-123)" or "Actress Name - Suffix (CODE-123)"
    private static final Pattern ACTRESS_PREFIX = Pattern.compile(
            "^(.+?)\\s*(?:-\\s*[^(]+)?\\s*\\(");

    private static final TitleCodeParser CODE_PARSER = new TitleCodeParser();

    /**
     * Extracts the actress name from a folder name like "Marin Yakuno (IPZZ-679)".
     * For multi-actress folders (comma-separated), returns only the first actress name.
     * Returns null if no name can be extracted.
     */
    public static String extractActressName(String folderName) {
        Matcher m = ACTRESS_PREFIX.matcher(folderName);
        if (!m.find()) return null;
        String rawName = m.group(1).trim();
        if (rawName.isEmpty()) return null;
        int comma = rawName.indexOf(',');
        if (comma > 0) rawName = rawName.substring(0, comma).trim();
        return rawName;
    }

    /**
     * Returns true if the folder name contains a parseable JAV code (e.g., "ABP-123")
     * anywhere in the string. Useful for conventional/queue volumes where folder names
     * are typically just the code itself.
     */
    public static boolean hasTitleCode(String folderName) {
        TitleCodeParser.ParsedCode parsed = CODE_PARSER.parse(folderName);
        // If parse couldn't find a code, it falls back to the raw folder name for both fields.
        return parsed.label() != null;
    }

    // Matches a JAV code inside parentheses: "(CODE-123)", "(CODE-123_4K)", "(CODE-123-AI)"
    private static final Pattern PARENTHESIZED_CODE = Pattern.compile(
            "\\([A-Za-z][A-Za-z0-9]{0,9}-\\d{2,6}[^)]*\\)");

    /**
     * Returns true if the folder name contains a JAV code inside parentheses.
     * This is the stricter heuristic used by exhibition scanning, where title folders
     * are always named like "Actress Name (CODE-123)" or just "(CODE-123)".
     * Prevents false positives on folder names like "xxx-av.com-21090-FHD".
     */
    public static boolean hasParenthesizedTitleCode(String folderName) {
        return PARENTHESIZED_CODE.matcher(folderName).find();
    }

    /**
     * Maps a star sub-partition id to an {@link Actress.Tier}.
     * Non-tier sub-partitions (favorites, archive) default to {@code LIBRARY}.
     */
    public static Actress.Tier toActressTier(String partitionId) {
        return switch (partitionId) {
            case "minor"     -> Actress.Tier.MINOR;
            case "popular"   -> Actress.Tier.POPULAR;
            case "superstar" -> Actress.Tier.SUPERSTAR;
            case "goddess"   -> Actress.Tier.GODDESS;
            default          -> Actress.Tier.LIBRARY;
        };
    }

    /**
     * Lists subdirectories of the given path. Returns an empty list if the path
     * doesn't exist.
     */
    public static List<Path> listSubdirectories(Path root, VolumeFileSystem fs) throws IOException {
        if (!fs.exists(root)) return List.of();
        return fs.listDirectory(root).stream()
                .filter(fs::isDirectory)
                .toList();
    }
}
