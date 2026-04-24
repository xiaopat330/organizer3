package com.organizer3.sandbox.trash;

import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.trash.TrashSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Fluent builder for creating synthetic trashed items under a per-test {@code _trash/} root.
 *
 * <p>Creates the item (1-byte file or empty directory) and its sidecar. Callers can suppress
 * the sidecar to simulate a failed sidecar write, and can control {@code trashedAt} so that
 * tests relying on mtime-based sort order can set reproducible timestamps.
 *
 * <p>Usage:
 * <pre>{@code
 * Path sidecar = new SandboxTrashBuilder(fs, trashRoot, "vol-a")
 *     .withOriginalPath("/stars/foo/MIDE-1")
 *     .withReason("test")
 *     .withTrashedAt(Instant.parse("2026-04-20T00:00:00Z"))
 *     .asFolder()
 *     .build();
 * }</pre>
 */
public class SandboxTrashBuilder {

    private final VolumeFileSystem fs;
    private final Path trashRoot;
    private final String volumeId;

    private String originalPath = "/test/ITEM-1";
    private String reason = "test";
    private Instant trashedAt = Instant.parse("2026-04-23T10:00:00Z");
    private Instant scheduledDeletionAt = null;
    private boolean writeSidecar = true;
    private boolean asFolder = false;

    public SandboxTrashBuilder(VolumeFileSystem fs, Path trashRoot, String volumeId) {
        this.fs = fs;
        this.trashRoot = trashRoot;
        this.volumeId = volumeId;
    }

    public SandboxTrashBuilder withOriginalPath(String path) {
        this.originalPath = path;
        return this;
    }

    public SandboxTrashBuilder withReason(String reason) {
        this.reason = reason;
        return this;
    }

    public SandboxTrashBuilder withTrashedAt(Instant t) {
        this.trashedAt = t;
        return this;
    }

    public SandboxTrashBuilder withScheduledDeletionAt(Instant t) {
        this.scheduledDeletionAt = t;
        return this;
    }

    /** Item will be created as a 1-byte file (default). */
    public SandboxTrashBuilder asFile() {
        this.asFolder = false;
        return this;
    }

    /** Item will be created as an empty directory. */
    public SandboxTrashBuilder asFolder() {
        this.asFolder = true;
        return this;
    }

    /** Omit the sidecar — simulates a failed sidecar write after a successful Trash.trashItem move. */
    public SandboxTrashBuilder withoutSidecar() {
        this.writeSidecar = false;
        return this;
    }

    /**
     * Creates the item and (optionally) its sidecar under {@code trashRoot}, mirroring the
     * original directory structure.
     *
     * @return the sidecar path (even if the sidecar was not written — the path is deterministic)
     */
    public Path build() throws IOException {
        // Mirror the original path structure under trashRoot
        String relative = originalPath.startsWith("/") ? originalPath.substring(1) : originalPath;
        Path itemPath = trashRoot.resolve(relative);
        Path sidecarPath = itemPath.getParent().resolve(itemPath.getFileName() + ".json");

        Files.createDirectories(itemPath.getParent());
        if (asFolder) {
            Files.createDirectories(itemPath);
        } else {
            Files.write(itemPath, new byte[]{0x00});
        }

        if (writeSidecar) {
            String scheduledStr = scheduledDeletionAt != null
                    ? scheduledDeletionAt.toString() : null;
            TrashSidecar sc = new TrashSidecar(
                    originalPath,
                    trashedAt.toString(),
                    volumeId,
                    reason,
                    scheduledStr,
                    null, null
            );
            sc.write(fs, sidecarPath);
            // Set the sidecar's mtime to match trashedAt so mtime-based sort is deterministic
            fs.setTimestamps(sidecarPath, null, trashedAt);
        }

        return sidecarPath;
    }
}
