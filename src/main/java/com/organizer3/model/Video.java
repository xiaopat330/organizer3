package com.organizer3.model;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * An individual video file belonging to a {@link Title}.
 * Maps directly to the {@code videos} DB table.
 *
 * <p>A title may have content split across multiple video files (multi-part releases),
 * each represented by one Video record.
 */
public record Video(
        Long id,
        Long titleId,
        String filename,
        Path path,
        LocalDate lastSeenAt
) {}
