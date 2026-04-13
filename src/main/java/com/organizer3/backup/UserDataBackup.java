package com.organizer3.backup;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Root object for a user-data backup file.
 *
 * <p>Contains snapshots of all user-altered fields from the database — favorites,
 * bookmarks, grades, visit history — keyed by stable identifiers (actress canonical
 * name, title code). These are the only fields that cannot be recovered through a
 * normal sync + {@code load actresses} workflow after a database drop.
 *
 * <p>{@code version} allows future format changes to be detected. The current version is
 * {@link UserDataBackupService#CURRENT_BACKUP_VERSION}. A backup file whose version is
 * higher than the parser supports will be rejected with a clear error rather than
 * misread silently.
 */
public record UserDataBackup(
        int version,
        LocalDateTime exportedAt,
        List<ActressBackupEntry> actresses,
        List<TitleBackupEntry> titles,
        List<WatchHistoryEntry> watchHistory
) {}
