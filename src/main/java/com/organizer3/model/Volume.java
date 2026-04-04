package com.organizer3.model;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.organizer.Utils.format;

/**
 * A Volume is a physical storage unit — a drive or network share. It has a short ID (e.g. "a", "bg", "hj"),
 * a mount path, and a structure type that determines its folder layout and available commands.
 * It is the top-level container in the content hierarchy:
 *
 * <pre>
 * Volume  (e.g. vol-a → /Volumes/ShareA)
 *   └── Partition  (e.g. "stars/popular", "queue", "archive")
 *         └── Actress folder  (structured partitions only)
 *               └── Title folder
 *                     └── Video files
 *         └── Title folder  (unstructured partitions — no actress level)
 *               └── Video files
 * </pre>
 */
public final class Volume {

    @Getter
    private final String id;

    @Getter
    private final Path mountPath;

    @Getter
    private final String structureType;

    @Getter
    private final Map<String, Partition> partitions = new HashMap<>();

    /** Timestamp of the last successful sync; null if this volume has never been synced. */
    @Getter @Setter
    private LocalDateTime lastSyncedAt;

    public Volume(@NonNull String id, @NonNull Path mountPath, @NonNull String structureType) {
        this.id = id;
        this.mountPath = mountPath;
        this.structureType = structureType;
    }

    public Optional<Partition> getPartition(String partitionId) {
        return Optional.ofNullable(partitions.get(partitionId));
    }

    @Override
    public String toString() {
        return format("[id:\"{0}\" path:\"{1}\"]", id, mountPath);
    }
}
