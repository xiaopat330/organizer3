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
}
