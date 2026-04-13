package com.organizer3.backup;

import java.time.LocalDateTime;

/**
 * Snapshot of user-altered fields for one title, keyed by {@code code}.
 * Used in {@link UserDataBackup} for export and restore.
 *
 * <p>{@code grade} is stored as the display string (e.g. {@code "S"}, {@code "A+"}).
 */
public record TitleBackupEntry(
        String code,
        boolean favorite,
        boolean bookmark,
        LocalDateTime bookmarkedAt,
        String grade,           // null when not graded; display string when set
        boolean rejected,
        int visitCount,
        LocalDateTime lastVisitedAt,
        String notes            // null when no notes
) {}
