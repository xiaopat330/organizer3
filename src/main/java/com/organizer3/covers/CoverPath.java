package com.organizer3.covers;

import com.organizer3.model.Title;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
    public Optional<Path> find(Title title) {
        if (title.getLabel() == null || title.getBaseCode() == null) return Optional.empty();
        Path dir = labelDir(title);
        if (!Files.isDirectory(dir)) return Optional.empty();

        String prefix = title.getBaseCode() + ".";
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(f -> f.getFileName().toString().startsWith(prefix))
                    .filter(f -> {
                        String ext = extensionOf(f.getFileName().toString());
                        return IMAGE_EXTENSIONS.contains(ext);
                    })
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
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
