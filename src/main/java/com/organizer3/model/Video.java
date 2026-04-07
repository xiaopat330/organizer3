package com.organizer3.model;

import lombok.Builder;
import lombok.Getter;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * An individual video file belonging to a {@link Title}.
 * Maps directly to the {@code videos} DB table.
 *
 * <p>A title may have content split across multiple video files (multi-part releases),
 * each represented by one Video record.
 */
@Getter
@Builder
public class Video {
    private final Long id;
    private final Long titleId;
    private final String filename;
    private final Path path;
    private final LocalDate lastSeenAt;
}
