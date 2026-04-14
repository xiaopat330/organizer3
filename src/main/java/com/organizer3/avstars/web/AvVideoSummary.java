package com.organizer3.avstars.web;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Lightweight representation of a single AV video, used in the actress detail video grid. */
@Value
@Builder
public class AvVideoSummary {
    long id;
    String filename;
    String relativePath;
    String bucket;
    Long sizeBytes;
    String resolution;
    String studio;
    String releaseDate;
    String parsedTitle;

    boolean favorite;
    boolean bookmark;
    boolean watched;
    int watchCount;
    String lastWatchedAt;

    /** Canonical tag slugs applied to this video via 'av tags apply'. Empty list if none. */
    List<String> tags;

    /** URL for the first screenshot thumbnail, e.g. {@code /api/av/screenshots/123/0}. Null if none taken. */
    String firstScreenshotUrl;

    /** Number of screenshots available for this video. 0 if none taken. */
    int screenshotCount;
}
