package com.organizer3.utilities.covers;

import com.organizer3.covers.CoverPath;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds and removes orphaned cover image files in the local covers cache — files under
 * {@link CoverPath#root()} whose base code has no matching title row in the database.
 *
 * <p>Covers are a regenerable local cache: re-running {@code sync covers} on a volume
 * that has the title will repopulate them. So deleting an orphan is safe; the only thing
 * we'd lose is a file that wasn't pointing at any DB row in the first place.
 *
 * <p>The {@link com.organizer3.utilities.health.checks.OrphanedCoversCheck Library Health
 * check} calls {@link #preview} to populate its findings, and the
 * {@code CleanOrphanedCoversTask} calls {@link #delete} under the atomic task lock so we
 * re-evaluate the predicate server-side — a stale client list can't cause an unintended
 * deletion.
 */
@Slf4j
public final class OrphanedCoversService {

    private final CoverPath coverPath;
    private final TitleRepository titles;

    public OrphanedCoversService(CoverPath coverPath, TitleRepository titles) {
        this.coverPath = coverPath;
        this.titles = titles;
    }

    /** Preview row: one per orphaned cover file. */
    public record OrphanRow(String label, String filename, String absolutePath, long sizeBytes) {
        public String baseCode() {
            int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(0, dot) : filename;
        }
    }

    /** Aggregate preview — full list of orphans + total size. No cap; consumers cap for display. */
    public record OrphanPreview(List<OrphanRow> rows, long totalBytes) {
        public int count() { return rows.size(); }
    }

    /** Result of a delete pass. */
    public record DeleteResult(int deleted, int failed, long bytesFreed) {}

    /**
     * Walk the covers root and return every orphaned file. Callers that only need the count
     * can use {@link OrphanPreview#count()}. Order is stable (label ASC, filename ASC).
     */
    public OrphanPreview preview() {
        Path root = coverPath.root();
        if (!Files.isDirectory(root)) return new OrphanPreview(List.of(), 0);

        Set<String> knownBaseCodes = titles.allBaseCodes();

        List<OrphanRow> rows = new ArrayList<>();
        long total = 0;
        try (DirectoryStream<Path> labelDirs = Files.newDirectoryStream(root)) {
            for (Path labelDir : labelDirs) {
                if (!Files.isDirectory(labelDir)) continue;
                String label = labelDir.getFileName().toString();
                try (DirectoryStream<Path> files = Files.newDirectoryStream(labelDir)) {
                    for (Path file : files) {
                        if (!Files.isRegularFile(file)) continue;
                        String name = file.getFileName().toString();
                        if (!CoverPath.isImageFile(name)) continue;
                        String baseCode = stripExtension(name);
                        if (knownBaseCodes.contains(baseCode)) continue;
                        long size = safeSize(file);
                        rows.add(new OrphanRow(label, name, file.toAbsolutePath().toString(), size));
                        if (size > 0) total += size;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk covers dir {}", root, e);
        }
        rows.sort((a, b) -> {
            int c = a.label.compareTo(b.label);
            return c != 0 ? c : a.filename.compareTo(b.filename);
        });
        return new OrphanPreview(rows, total);
    }

    /**
     * Re-scan and delete every orphaned cover. Returns counts + bytes freed. A file that was
     * present at preview time but has since been removed (by sync, by the user, etc.) is not
     * counted as failed — the goal is the same-as-preview end state.
     */
    public DeleteResult delete() {
        OrphanPreview pv = preview();
        int deleted = 0;
        int failed = 0;
        long freed = 0;
        for (OrphanRow row : pv.rows()) {
            Path p = Path.of(row.absolutePath());
            try {
                if (Files.deleteIfExists(p)) {
                    deleted++;
                    if (row.sizeBytes > 0) freed += row.sizeBytes;
                }
            } catch (IOException e) {
                failed++;
                log.warn("Failed to delete orphaned cover {}", p, e);
            }
        }
        return new DeleteResult(deleted, failed, freed);
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return -1; }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
