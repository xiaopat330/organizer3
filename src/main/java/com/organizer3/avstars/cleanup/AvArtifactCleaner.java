package com.organizer3.avstars.cleanup;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Deletes local AV-stars artifacts — screenshot directories and headshot files — that would
 * otherwise be orphaned when their owning DB rows disappear.
 *
 * <p>Mirrors the {@code CoverPath}-driven cleanup now folded into sync: the owning DB row and
 * its local artifact should die in the same transaction (logically, if not literally). Callers
 * must invoke the appropriate method <em>before</em> running the SQL delete, since once the
 * row is gone we can't recover the artifact path.
 *
 * <p>Path layout:
 * <ul>
 *   <li>Screenshots: {@code <screenshotDir>/<videoId>/<seq>.jpg}. Delete the whole
 *       {@code <videoId>/} subtree when the video is removed.</li>
 *   <li>Headshots: a bare filename or path stored in {@code av_actresses.headshot_path};
 *       lives directly under {@code <headshotDir>/}.</li>
 * </ul>
 */
@Slf4j
public final class AvArtifactCleaner {

    private final Path screenshotDir;
    private final Path headshotDir;

    public AvArtifactCleaner(Path screenshotDir, Path headshotDir) {
        this.screenshotDir = screenshotDir;
        this.headshotDir = headshotDir;
    }

    /**
     * Delete the on-disk screenshot directory for each video id. Missing directories are
     * ignored (may legitimately never have been populated). Returns the count of directories
     * actually removed.
     */
    public int deleteScreenshotsFor(Collection<Long> videoIds) {
        int removed = 0;
        for (Long id : videoIds) {
            Path dir = screenshotDir.resolve(String.valueOf(id));
            if (!Files.isDirectory(dir)) continue;
            try {
                deleteTree(dir);
                removed++;
            } catch (IOException e) {
                log.warn("Failed to delete screenshot dir {}", dir, e);
            }
        }
        return removed;
    }

    /**
     * Delete a single headshot file. {@code headshotPath} is what's stored in
     * {@code av_actresses.headshot_path} — either a bare filename or a relative path; only
     * the filename is used (we always resolve under {@code headshotDir}). No-op on null/blank.
     * Returns true if the file existed and was deleted.
     */
    public boolean deleteHeadshot(String headshotPath) {
        if (headshotPath == null || headshotPath.isBlank()) return false;
        String filename = Path.of(headshotPath).getFileName().toString();
        Path file = headshotDir.resolve(filename);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete headshot {}", file, e);
            return false;
        }
    }

    /** Recursive delete: files first, then directories (depth-first post-order). */
    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) {
                    log.debug("deleteTree failed for {}", p, e);
                }
            });
        }
    }
}
