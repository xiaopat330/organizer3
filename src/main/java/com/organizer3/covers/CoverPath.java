package com.organizer3.covers;

import com.organizer3.model.Title;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves local cover image paths for titles.
 *
 * <p>Cover images are stored under {@code <dataDir>/covers/<LABEL>/<baseCode>.<ext>}.
 * The path is deterministic from the title's
 * {@code label} and {@code baseCode} fields — no database lookup required.
 *
 * <p>Since titles may have different image formats (jpg, png, webp), the actual
 * extension is preserved from the source. Use {@link #find(Title)} to locate an
 * existing cover regardless of extension.
 */
public class CoverPath {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff", "tif"
    );

    private final Path coversRoot;

    public CoverPath(Path dataDir) {
        this.coversRoot = dataDir.resolve("covers");
    }

    /** Returns the covers root directory. */
    public Path root() {
        return coversRoot;
    }

    /** Returns the label directory for a given title (e.g. {@code data/covers/ABP/}). */
    public Path labelDir(Title title) {
        return coversRoot.resolve(title.getLabel().toUpperCase());
    }

    /**
     * Returns the full path for a cover image with the given extension.
     * E.g. {@code data/covers/ABP/ABP-00123.jpg}
     */
    public Path resolve(Title title, String extension) {
        return labelDir(title).resolve(title.getBaseCode() + "." + extension);
    }

    /**
     * Finds an existing cover image for the title, regardless of extension.
     * Returns empty if no cover exists or if the title has no label/baseCode.
     */
    // Ordered by likelihood — jpg is by far the most common cover format
    private static final List<String> PROBE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp", "gif");

    public Optional<Path> find(Title title) {
        if (title.getLabel() == null || title.getBaseCode() == null) return Optional.empty();
        Path dir = labelDir(title);
        if (!Files.isDirectory(dir)) return Optional.empty();

        // Probe specific filenames rather than listing the whole directory.
        // Cover directories can have 700+ files — Files.list() is slow at that scale.
        String base = title.getBaseCode();
        for (String ext : PROBE_EXTENSIONS) {
            Path candidate = dir.resolve(base + "." + ext);
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Finds a cover by raw title code (e.g. {@code "ABP-00123"}), without needing a {@link Title}.
     * The label is inferred as the alpha prefix before the first dash.
     */
    public Optional<Path> findByCode(String titleCode) {
        if (titleCode == null) return Optional.empty();
        int dash = titleCode.indexOf('-');
        if (dash <= 0) return Optional.empty();
        String label = titleCode.substring(0, dash).toUpperCase();
        Path dir = coversRoot.resolve(label);
        if (!Files.isDirectory(dir)) return Optional.empty();
        for (String ext : PROBE_EXTENSIONS) {
            Path candidate = dir.resolve(titleCode + "." + ext);
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Returns true if a cover image already exists locally for this title. */
    public boolean exists(Title title) {
        return find(title).isPresent();
    }

    /**
     * Returns true if the filename has a recognized image extension.
     */
    public static boolean isImageFile(String filename) {
        return IMAGE_EXTENSIONS.contains(extensionOf(filename));
    }

    /** Extracts the lowercase file extension, or empty string if none. */
    public static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
