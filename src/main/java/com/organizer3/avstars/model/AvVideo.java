package com.organizer3.avstars.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * A video file under an AV actress folder. Maps to the {@code av_videos} DB table.
 *
 * <p>Identity is (avActressId, relativePath) — the path relative to the actress's
 * root folder, which disambiguates files with the same name in different sub-buckets.
 *
 * <p>Parsed fields ({@code studio}, {@code releaseDate}, etc.) are populated in a
 * separate pass by {@code AvFilenameParser} and are all nullable.
 */
@Value
@Builder
public class AvVideo {

    Long id;
    long avActressId;
    String volumeId;

    // Location
    String relativePath;
    String filename;
    String extension;
    Long sizeBytes;
    String mtime;
    LocalDateTime lastSeenAt;
    String addedDate;

    /** First-level subfolder name under the actress root (e.g. "old", "new", "keep"). Null if at root. */
    String bucket;

    // Parsed from filename (all nullable — populated separately)
    String studio;
    String releaseDate;
    String parsedTitle;
    String resolution;
    String codec;
    String tagsJson;

    // Curation
    @Builder.Default boolean favorite = false;
    @Builder.Default boolean rejected = false;

    // Watch/curation tracking (v15)
    @Builder.Default boolean bookmark = false;
    @Builder.Default boolean watched = false;
    String lastWatchedAt;
    @Builder.Default int watchCount = 0;
}
