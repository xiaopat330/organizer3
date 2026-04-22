package com.organizer3.backup;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Pre-flight summary of what {@link UserDataBackupService#restore} would do if invoked with a
 * given backup file. Purely read-only — no DB writes. Feeds the Utilities Backup screen's
 * visualize pane so the user sees scope and composition before committing.
 *
 * <p>"Existing" means the backup entry maps to a row currently in the DB; those rows would be
 * updated. "Missing" means the entry references a name/code not yet synced; those are silently
 * skipped by the restore path. "WouldInsert" applies only to watch history — rows that don't
 * already exist.
 */
public record RestorePreview(
        int backupVersion,
        LocalDateTime backupExportedAt,
        Category actresses,
        Category titles,
        Category watchHistory,
        Category avActresses,
        Category avVideos) {

    /**
     * @param name          human label, e.g. "actresses"
     * @param existing      entries whose target row exists in the DB (would be updated)
     * @param missing       entries with no matching DB row (would be skipped)
     * @param wouldInsert   for watch-history category only — entries that would be newly inserted.
     *                      Zero for other categories.
     * @param sampleIds     up to ~10 identifiers of "existing" rows, for visual feel
     */
    public record Category(String name,
                           int existing,
                           int missing,
                           int wouldInsert,
                           List<String> sampleIds) {
        public Category { sampleIds = List.copyOf(sampleIds); }

        public int total() { return existing + missing + wouldInsert; }
    }
}
