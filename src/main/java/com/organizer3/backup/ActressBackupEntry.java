package com.organizer3.backup;

import java.time.LocalDateTime;

/**
 * Snapshot of user-altered fields for one actress, keyed by {@code canonicalName}.
 * Used in {@link UserDataBackup} for export and restore.
 *
 * <p>{@code grade} is stored as the display string (e.g. {@code "A+"}, {@code "SSS"})
 * rather than the enum name so the JSON file is human-readable and stable across renames.
 */
public record ActressBackupEntry(
        String canonicalName,
        boolean favorite,
        boolean bookmark,
        LocalDateTime bookmarkedAt,
        String grade,           // null when not graded; display string (e.g. "A+") when set
        boolean rejected,
        int visitCount,
        LocalDateTime lastVisitedAt
) {}
