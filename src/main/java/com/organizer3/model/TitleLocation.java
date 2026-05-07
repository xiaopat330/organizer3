package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A physical location where a {@link Title} exists on a volume.
 * A title may exist in multiple locations (different volumes, different partitions,
 * or even multiple copies on the same volume).
 */
@Value
@Builder(toBuilder = true)
public class TitleLocation {
    Long id;
    long titleId;
    String volumeId;
    String partitionId;
    Path path;
    LocalDate lastSeenAt;
    LocalDate addedDate;
    /**
     * ISO-8601 timestamp at which this row was first marked absent by a sync pass.
     * {@code null} means the row is live (observed during the most recent sync of its scope).
     * Non-null means the row is stale and will be swept after {@code sync.staleGraceDays} days.
     */
    Instant staleSince;

    /** Returns true if this location is stale (not observed on the last sync of its scope). */
    public boolean isStale() {
        return staleSince != null;
    }
}
