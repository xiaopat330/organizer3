package com.organizer3.utilities.backup;

import com.organizer3.backup.UserDataBackupService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only catalog of on-disk backup snapshots for the Utilities Backup screen. Enumerates
 * files via {@link UserDataBackupService#findSnapshots}, enriches each with its size and
 * parsed timestamp, flags the newest as {@code latest}. Parsing the snapshot filename itself
 * (rather than reading the JSON) keeps the list endpoint cheap.
 *
 * <p>Also serves as the gatekeeper for snapshot-path validation: task run endpoints route
 * their {@code snapshotName} inputs through {@link #resolve(String)} so a client can never
 * reference an arbitrary filesystem path.
 */
public final class BackupCatalogService {

    /**
     * Must mirror the format in {@code UserDataBackupService.snapshotPathFor} — colons are
     * replaced with hyphens when writing; we reverse that when parsing.
     */
    private static final DateTimeFormatter SNAPSHOT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private final UserDataBackupService service;
    private final Path basePath;

    public BackupCatalogService(UserDataBackupService service, Path basePath) {
        this.service = service;
        this.basePath = basePath;
    }

    public List<Snapshot> list() {
        List<Path> paths = service.findSnapshots(basePath);
        // findSnapshots returns oldest-first; the screen renders newest-first, so reverse.
        List<Snapshot> out = new ArrayList<>(paths.size());
        for (int i = paths.size() - 1; i >= 0; i--) {
            Snapshot s = toSnapshot(paths.get(i), i == paths.size() - 1);
            if (s != null) out.add(s);
        }
        return out;
    }

    public Optional<Path> resolve(String snapshotName) {
        if (snapshotName == null || snapshotName.isBlank()) return Optional.empty();
        // Reject any path-ish input. The name must be a single filename — no slashes, no `..`.
        if (snapshotName.contains("/") || snapshotName.contains("\\")
                || snapshotName.contains("..")) return Optional.empty();
        Path resolved = basePath.getParent().resolve(snapshotName);
        // Belt-and-suspenders: ensure the resolved file is inside the backups directory.
        if (!resolved.getParent().equals(basePath.getParent())) return Optional.empty();
        if (!Files.isRegularFile(resolved)) return Optional.empty();
        return Optional.of(resolved);
    }

    private Snapshot toSnapshot(Path p, boolean latest) {
        String name = p.getFileName().toString();
        long size;
        try { size = Files.size(p); } catch (IOException e) { size = 0L; }
        LocalDateTime timestamp = parseTimestamp(name);
        return new Snapshot(name, size, timestamp, latest);
    }

    /**
     * Parse the trailing timestamp from {@code <stem>-yyyy-MM-ddTHH-mm-ss.json}. Returns null
     * for filenames that don't match the convention (robust against user-dropped files).
     */
    private LocalDateTime parseTimestamp(String filename) {
        if (!filename.endsWith(".json")) return null;
        String stem = filename.substring(0, filename.length() - 5);
        // Timestamp is 19 chars: 2026-04-22T02-10-00. Walk back 19 chars from end.
        if (stem.length() < 20) return null;
        String ts = stem.substring(stem.length() - 19);
        try {
            return LocalDateTime.parse(ts, SNAPSHOT_TS);
        } catch (Exception e) {
            return null;
        }
    }

    public record Snapshot(String name, long sizeBytes, LocalDateTime timestamp, boolean latest) {}
}
