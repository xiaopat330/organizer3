package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for the user-data backup feature. Bound from the {@code backup:} block in
 * {@code organizer-config.yaml}. All fields are optional — defaults are applied in the service.
 *
 * <pre>
 * backup:
 *   autoBackupIntervalMinutes: 30
 *   snapshotCount: 10
 * </pre>
 *
 * <p>Backup files are written to {@code <dataDir>/backups/user-data-backup.json}
 * (or timestamped snapshots in the same directory when {@code snapshotCount > 0}).
 */
public record BackupConfig(
        /**
         * How often to write an automatic backup, in minutes.
         * {@code 0} or {@code null} disables auto-backup. Default: {@code 0}.
         */
        @JsonProperty("autoBackupIntervalMinutes") Integer autoBackupIntervalMinutes,

        /**
         * Number of timestamped snapshot files to keep. When {@code > 0}, each backup
         * writes a new file named {@code <stem>-<timestamp>.json} and the oldest files
         * beyond this count are deleted. {@code 0} or {@code null} disables snapshots —
         * a single file is overwritten each time.
         */
        @JsonProperty("snapshotCount") Integer snapshotCount
) {}
