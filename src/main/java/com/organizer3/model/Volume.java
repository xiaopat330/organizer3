package com.organizer3.model;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.text.MessageFormat;
import java.time.LocalDateTime;

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
@Getter
@RequiredArgsConstructor
public final class Volume {

    @NonNull private final String id;
    @NonNull private final String structureType;

    /** Timestamp of the last successful sync; null if this volume has never been synced. */
    @Setter
    private LocalDateTime lastSyncedAt;

    @Override
    public String toString() {
        return MessageFormat.format("[id:\"{0}\"]", id);
    }
}
