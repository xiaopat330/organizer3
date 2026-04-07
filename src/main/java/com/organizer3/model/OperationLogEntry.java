package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * An audit record of a single file operation performed (or simulated in dry-run) by the organizer.
 * Maps directly to the {@code operations} DB table.
 *
 * <p>Operations are always logged regardless of armed/dry-run mode — {@code wasArmed}
 * distinguishes whether the operation was actually executed or only simulated.
 */
@Value
@Builder
public class OperationLogEntry {

    public enum OperationType {
        MOVE, RENAME, CREATE_DIRECTORY
    }

    Long id;
    LocalDateTime timestamp;
    OperationType type;
    Path sourcePath;
    Path destPath;
    boolean wasArmed;
}
