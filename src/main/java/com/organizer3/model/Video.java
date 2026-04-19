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

    // Media metadata. All nullable.
    //
    // durationSec/width/height/codecs/container are populated by VideoProbe (HTTP stream).
    // sizeBytes is populated at sync time via VolumeFileSystem.size(). There is no
    // "size backfill" path yet — rows that predate v19 stay null until their partition
    // is re-synced. findUnprobed keys on {@code duration_sec IS NULL} only.
    Long    durationSec;
    Integer width;
    Integer height;
    String  videoCodec;
    String  audioCodec;
    /** Derived from filename extension — e.g. "mkv", "mp4", "avi". Lowercase, no dot. */
    String  container;
    /** File size in bytes. Captured at sync time via {@code VolumeFileSystem.size()}. */
    Long    sizeBytes;
}
