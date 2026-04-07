package com.organizer3.model;

import lombok.Builder;
import lombok.Getter;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * An audit record of a single file operation performed (or simulated in dry-run) by the organizer.
 * Maps directly to the {@code operations} DB table.
 *
 * <p>Operations are always logged regardless of armed/dry-run mode — {@code wasArmed}
 * distinguishes whether the operation was actually executed or only simulated.
 */
@Getter
@Builder
public class OperationLogEntry {

    public enum OperationType {
        MOVE, RENAME, CREATE_DIRECTORY
    }

    private final Long id;
    private final LocalDateTime timestamp;
    private final OperationType type;
    private final Path sourcePath;
    private final Path destPath;
    private final boolean wasArmed;
}
