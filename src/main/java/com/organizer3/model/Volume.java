package com.organizer3.model;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.organizer3.utils.Utils.format;

/**
 * A Volume is a physical storage unit — a drive or network share. It has a short ID (e.g. "a", "bg", "hj")
 * and a structure type that determines its folder layout and available commands.
 * It is the top-level container in the content hierarchy:
 *
 * <pre>
 * Volume  (e.g. "a" → //pandora/jav_A)
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
    private final String structureType;

    @Getter
    private final Map<String, Partition> partitions = new HashMap<>();

    /** Timestamp of the last successful sync; null if this volume has never been synced. */
    @Getter @Setter
    private LocalDateTime lastSyncedAt;

    public Volume(@NonNull String id, @NonNull String structureType) {
        this.id = id;
        this.structureType = structureType;
    }

    public Optional<Partition> getPartition(String partitionId) {
        return Optional.ofNullable(partitions.get(partitionId));
    }

    @Override
    public String toString() {
        return format("[id:\"{0}\"]", id);
    }
}
