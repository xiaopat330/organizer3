package com.organizer3.backup;

/**
 * Snapshot of user-altered fields for one AV video, keyed by
 * {@code (volumeId, folderName, relativePath)}.
 * Used in {@link UserDataBackup} for export and restore.
 */
public record AvVideoBackupEntry(
        String volumeId,
        String folderName,
        String relativePath,
        boolean favorite,
        boolean bookmark,
        boolean watched,
        int watchCount,
        String lastWatchedAt    // ISO string from the DB; null when never watched
) {}
