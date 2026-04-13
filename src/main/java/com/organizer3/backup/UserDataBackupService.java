package com.organizer3.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Exports and restores user-altered database fields to/from a JSON backup file.
 *
 * <p>User data is defined as anything set by user action or behavior — favorites,
 * bookmarks, grades, reject flags, visit counts, and watch history. These fields are
 * not recoverable through a normal sync + {@code load actresses} workflow after a
 * database drop, making a periodic backup essential.
 *
 * <p>The backup format is versioned. A file whose {@code version} exceeds
 * {@link #CURRENT_BACKUP_VERSION} will be rejected with a clear error.
 */
@Slf4j
@RequiredArgsConstructor
public class UserDataBackupService {

    public static final int CURRENT_BACKUP_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final WatchHistoryRepository watchHistoryRepo;

    /**
     * Build a {@link UserDataBackup} from the current database state.
     * Only includes actresses and titles that have at least one non-default user field.
     */
    public UserDataBackup export() {
        List<ActressBackupEntry> actresses = actressRepo.findAllForBackup().stream()
                .map(r -> new ActressBackupEntry(
                        r.canonicalName(), r.favorite(), r.bookmark(), r.bookmarkedAt(),
                        r.grade(), r.rejected(), r.visitCount(), r.lastVisitedAt()))
                .toList();

        List<TitleBackupEntry> titles = titleRepo.findAllForBackup().stream()
                .map(r -> new TitleBackupEntry(
                        r.code(), r.favorite(), r.bookmark(), r.bookmarkedAt(),
                        r.grade(), r.rejected(), r.visitCount(), r.lastVisitedAt(), r.notes()))
                .toList();

        List<WatchHistoryEntry> watchHistory = watchHistoryRepo.findAllEntries().stream()
                .map(h -> new WatchHistoryEntry(h.getTitleCode(), h.getWatchedAt()))
                .toList();

        return new UserDataBackup(CURRENT_BACKUP_VERSION, LocalDateTime.now(),
                actresses, titles, watchHistory);
    }

    /**
     * Serialize a backup to JSON and write it to {@code path}.
     * Creates parent directories if they do not exist.
     */
    public void write(UserDataBackup backup, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), backup);
        log.info("Backup written: {} actresses, {} titles, {} watch history entries → {}",
                backup.actresses().size(), backup.titles().size(),
                backup.watchHistory().size(), path);
    }

    /**
     * Read and parse a backup file.
     *
     * @throws IOException                   if the file cannot be read or parsed
     * @throws UnsupportedBackupVersionException if the file's version exceeds what this parser supports
     */
    public UserDataBackup read(Path path) throws IOException {
        UserDataBackup backup = MAPPER.readValue(path.toFile(), UserDataBackup.class);
        if (backup.version() > CURRENT_BACKUP_VERSION) {
            throw new UnsupportedBackupVersionException(backup.version(), CURRENT_BACKUP_VERSION);
        }
        return backup;
    }

    /**
     * Overlay user-altered fields from {@code backup} onto the current database.
     *
     * <p>This is an overlay, not a replace — rows that have no corresponding backup entry
     * are left unchanged. Actress and title entries that reference names/codes not yet in
     * the database are skipped (expected when restoring before all volumes have been synced).
     * Watch history insertions are idempotent via {@code INSERT OR IGNORE}.
     */
    public RestoreResult restore(UserDataBackup backup) {
        int actressesRestored = 0, actressesSkipped = 0;
        int titlesRestored = 0, titlesSkipped = 0;
        int watchHistoryInserted = 0;

        for (ActressBackupEntry entry : backup.actresses()) {
            boolean exists = actressRepo.findByCanonicalName(entry.canonicalName()).isPresent();
            if (!exists) {
                actressesSkipped++;
            } else {
                actressRepo.restoreUserData(
                        entry.canonicalName(), entry.favorite(), entry.bookmark(),
                        entry.bookmarkedAt(), entry.grade(), entry.rejected(),
                        entry.visitCount(), entry.lastVisitedAt());
                actressesRestored++;
            }
        }

        for (TitleBackupEntry entry : backup.titles()) {
            boolean exists = titleRepo.findByCode(entry.code()).isPresent();
            if (!exists) {
                titlesSkipped++;
            } else {
                titleRepo.restoreUserData(
                        entry.code(), entry.favorite(), entry.bookmark(),
                        entry.bookmarkedAt(), entry.grade(), entry.rejected(),
                        entry.visitCount(), entry.lastVisitedAt(), entry.notes());
                titlesRestored++;
            }
        }

        for (WatchHistoryEntry entry : backup.watchHistory()) {
            if (watchHistoryRepo.insertOrIgnore(entry.titleCode(), entry.watchedAt())) {
                watchHistoryInserted++;
            }
        }

        return new RestoreResult(actressesRestored, actressesSkipped,
                titlesRestored, titlesSkipped, watchHistoryInserted);
    }

    /** Convenience: export and immediately write to {@code path}. Used by auto-backup. */
    public void exportAndWrite(Path path) throws IOException {
        write(export(), path);
    }

    // ── Snapshot support ─────────────────────────────────────────────────────

    /**
     * Timestamp format used in snapshot filenames. Colons are replaced with hyphens
     * so the name is valid on all filesystems.
     * Example: {@code 2026-04-13T14-30-00}
     */
    private static final DateTimeFormatter SNAPSHOT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    /**
     * Derive the timestamped snapshot path from a base path and a timestamp.
     *
     * <p>Example: {@code data/user-data-backup.json} + {@code 2026-04-13T14:30:00}
     * → {@code data/user-data-backup-2026-04-13T14-30-00.json}
     */
    public static Path snapshotPathFor(Path basePath, LocalDateTime timestamp) {
        String filename = basePath.getFileName().toString();
        String stem = filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
        String snapshotName = stem + "-" + SNAPSHOT_TS.format(timestamp) + ".json";
        return basePath.getParent().resolve(snapshotName);
    }

    /**
     * Export to a timestamped snapshot file, then prune old snapshots so at most
     * {@code snapshotCount} files are kept (oldest deleted first).
     *
     * @return the path of the newly written snapshot
     */
    public Path exportAndWriteSnapshot(Path basePath, int snapshotCount) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        Path snapshotPath = snapshotPathFor(basePath, now);
        write(export(), snapshotPath);
        pruneSnapshots(basePath, snapshotCount);
        return snapshotPath;
    }

    /**
     * Return all snapshot files for {@code basePath}, sorted oldest-first.
     * Snapshot files are those in the same directory whose name matches
     * {@code <stem>-<timestamp>.json}.
     */
    public List<Path> findSnapshots(Path basePath) {
        String stem = snapshotStem(basePath);
        Path dir = basePath.getParent();
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(stem + "-") && name.endsWith(".json");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list snapshots in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * Find the best file to restore from: the newest snapshot if any exist,
     * otherwise the plain base path if it exists.
     */
    public Optional<Path> findLatestBackup(Path basePath) {
        List<Path> snapshots = findSnapshots(basePath);
        if (!snapshots.isEmpty()) {
            return Optional.of(snapshots.get(snapshots.size() - 1));
        }
        return Files.exists(basePath) ? Optional.of(basePath) : Optional.empty();
    }

    /** Delete the oldest snapshots beyond {@code keep}. */
    private void pruneSnapshots(Path basePath, int keep) {
        List<Path> snapshots = findSnapshots(basePath);
        int excess = snapshots.size() - keep;
        for (int i = 0; i < excess; i++) {
            try {
                Files.delete(snapshots.get(i));
                log.info("Pruned old snapshot: {}", snapshots.get(i).getFileName());
            } catch (IOException e) {
                log.warn("Could not delete old snapshot {}: {}", snapshots.get(i), e.getMessage());
            }
        }
    }

    private static String snapshotStem(Path basePath) {
        String filename = basePath.getFileName().toString();
        return filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
    }
}
