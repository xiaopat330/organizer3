package com.organizer3.backup;

/** Counts from a {@link UserDataBackupService#restore} call. */
public record RestoreResult(
        int actressesRestored,
        int actressesSkipped,
        int titlesRestored,
        int titlesSkipped,
        int watchHistoryInserted
) {}
