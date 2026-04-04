package com.organizer3.model;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * An audit record of a single file operation performed (or simulated in dry-run) by the organizer.
 * Maps directly to the {@code operations} DB table.
 *
 * <p>Operations are always logged regardless of armed/dry-run mode — {@code wasArmed}
 * distinguishes whether the operation was actually executed or only simulated.
 */
public record OperationLogEntry(
        Long id,
        LocalDateTime timestamp,
        OperationType type,
        Path sourcePath,
        Path destPath,
        boolean wasArmed
) {
    public enum OperationType {
        MOVE, RENAME, CREATE_DIRECTORY
    }
}
