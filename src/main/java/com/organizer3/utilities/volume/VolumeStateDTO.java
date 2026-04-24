package com.organizer3.utilities.volume;

import java.util.List;

/**
 * JSON-serializable snapshot of a volume's user-facing state for the Volumes screen.
 *
 * @param id            stable volume identifier (e.g. "a")
 * @param smbPath       network path (e.g. "//pandora/jav_A") — shown as muted subtitle
 * @param structureType e.g. "conventional", "exhibition"
 * @param lastSyncedAt  ISO-8601 string (e.g. "2026-04-19T11:43:44.659488"), or null if never synced
 * @param titleCount    number of titles with at least one location on the volume
 * @param queueCount    number of title_locations rows in the "queue" partition; 0 for non-queue volumes
 * @param status        "online" or "offline"
 * @param health        zero or more issues; empty list renders as "All healthy"
 */
public record VolumeStateDTO(
        String id,
        String smbPath,
        String structureType,
        String lastSyncedAt,
        int titleCount,
        int queueCount,
        String status,
        List<HealthIssue> health) {

    public VolumeStateDTO {
        health = List.copyOf(health);
    }

    /**
     * @param level       "warn" or "error"
     * @param category    short key the UI can key off (e.g. "orphan_covers", "missing_thumbs")
     * @param count       affected-item count (0 if N/A)
     * @param description human-readable one-liner for the UI
     */
    public record HealthIssue(String level, String category, int count, String description) {}
}
