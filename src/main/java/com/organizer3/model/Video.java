package com.organizer3.model;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * An individual video file belonging to a {@link Title}.
 * Maps directly to the {@code videos} DB table.
 *
 * <p>A title may have content split across multiple video files (multi-part releases),
 * each represented by one Video record.
 */
@Value
@Builder(toBuilder = true)
public class Video {
    Long id;
    Long titleId;
    String volumeId;
    String filename;
    Path path;
    LocalDate lastSeenAt;

    // Media metadata — populated by VideoProbe during sync or backfill. All nullable;
    // {@code durationSec == null} is the "needs probing" signal used by the backfill command.
    Long    durationSec;
    Integer width;
    Integer height;
    String  videoCodec;
    String  audioCodec;
    /** Derived from filename extension — e.g. "mkv", "mp4", "avi". Lowercase, no dot. */
    String  container;
}
