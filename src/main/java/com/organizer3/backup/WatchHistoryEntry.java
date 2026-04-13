package com.organizer3.backup;

import java.time.LocalDateTime;

/** One watch event in a {@link UserDataBackup}. Keyed on (titleCode, watchedAt). */
public record WatchHistoryEntry(
        String titleCode,
        LocalDateTime watchedAt
) {}
