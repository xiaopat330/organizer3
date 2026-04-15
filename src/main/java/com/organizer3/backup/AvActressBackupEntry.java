package com.organizer3.backup;

/**
 * Snapshot of user-altered fields for one AV actress, keyed by {@code (volumeId, folderName)}.
 * Used in {@link UserDataBackup} for export and restore.
 *
 * <p>{@code grade} is stored as the display string (e.g. {@code "A+"}) rather than an enum
 * name so the JSON file is human-readable and stable across renames.
 */
public record AvActressBackupEntry(
        String volumeId,
        String folderName,
        boolean favorite,
        boolean bookmark,
        boolean rejected,
        String grade,           // null when not graded; display string (e.g. "A+") when set
        String notes,           // null when no notes
        int visitCount,
        String lastVisitedAt    // ISO string from the DB; null when never visited
) {}
