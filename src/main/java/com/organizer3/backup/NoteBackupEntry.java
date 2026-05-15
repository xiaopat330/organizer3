package com.organizer3.backup;

/**
 * Snapshot of a single note row for backup/restore.
 *
 * <p>{@code entityType} is stored as the wire/DB string ({@code "actress"} or
 * {@code "title"}) rather than the enum name, keeping the JSON file human-readable
 * and symmetric with the HTTP API and DB column.
 *
 * <p>{@code createdAt} and {@code updatedAt} are epoch millis, preserved exactly
 * so that a restore produces timestamp-faithful rows.
 */
public record NoteBackupEntry(
        String entityType,   // wire value: "actress" or "title"
        String entityId,
        String body,
        long createdAt,
        long updatedAt
) {}
