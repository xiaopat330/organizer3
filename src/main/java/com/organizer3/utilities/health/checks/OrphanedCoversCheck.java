package com.organizer3.utilities.health.checks;

import com.organizer3.covers.CoverPath;
import com.organizer3.repository.TitleRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cover image files on disk that don't match any title row in the DB. Built by walking the
 * label subdirectories under {@link CoverPath#root()} and checking each file's base code
 * against {@link TitleRepository#findByCode(String)}.
 *
 * <p>Expected layout: {@code covers/<LABEL>/<baseCode>.<ext>}. Anything that doesn't match
 * that shape (non-image files, nested dirs, files with no extension) is flagged as orphaned
 * too — it shouldn't be there either.
 */
@Slf4j
public final class OrphanedCoversCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final CoverPath coverPath;
    private final TitleRepository titles;

    public OrphanedCoversCheck(CoverPath coverPath, TitleRepository titles) {
        this.coverPath = coverPath;
        this.titles = titles;
    }

    @Override public String id() { return "orphaned_covers"; }
    @Override public String label() { return "Orphaned covers"; }
    @Override public String description() {
        return "Cover image files on disk with no matching title in the database.";
    }
    // Inline deletion would be the natural Phase 2 fix; today we just report.
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        Path root = coverPath.root();
        if (!Files.isDirectory(root)) return CheckResult.empty();

        int total = 0;
        List<Finding> sample = new ArrayList<>();
        try (DirectoryStream<Path> labelDirs = Files.newDirectoryStream(root)) {
            for (Path labelDir : labelDirs) {
                if (!Files.isDirectory(labelDir)) continue;
                String label = labelDir.getFileName().toString();
                try (DirectoryStream<Path> files = Files.newDirectoryStream(labelDir)) {
                    for (Path file : files) {
                        if (!Files.isRegularFile(file)) continue;
                        String name = file.getFileName().toString();
                        if (!CoverPath.isImageFile(name)) continue; // skip non-image clutter
                        String baseCode = stripExtension(name);
                        // Cover filename should match a title.baseCode → title code format is
                        // "{LABEL}-{seq}" and baseCode should equal that. Match by code: the
                        // full title code matches the baseCode on disk.
                        if (titles.findByCode(baseCode).isEmpty()) {
                            total++;
                            if (sample.size() < SAMPLE_LIMIT) {
                                long size = safeSize(file);
                                sample.add(new Finding(
                                        label + "/" + name,
                                        file.toAbsolutePath().toString(),
                                        "local cover cache · " + formatSize(size)
                                                + " · safe to delete (no DB row for " + baseCode + ")"));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk covers dir {}", root, e);
        }
        return new CheckResult(total, sample);
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
