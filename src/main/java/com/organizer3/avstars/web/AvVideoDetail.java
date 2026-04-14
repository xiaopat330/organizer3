package com.organizer3.avstars.web;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Full detail view of a single AV video — returned by {@code GET /api/av/videos/{id}}.
 *
 * <p>All parsed metadata fields are nullable (not all filenames yield every field).
 * {@code smbUrl} is null when the video's volume is not found in the app config
 * (e.g. volume offline or not configured).
 */
@Value
@Builder
public class AvVideoDetail {

    long id;
    long actressId;
    String actressStageName;
    String actressFolderName;
    String volumeId;

    // Location
    String filename;
    String relativePath;
    String extension;
    String bucket;
    Long sizeBytes;
    String mtime;
    String addedDate;

    // Parsed from filename (all nullable)
    String studio;
    String releaseDate;
    String parsedTitle;
    String resolution;
    String codec;

    // Curation
    boolean favorite;
    boolean bookmark;
    boolean watched;
    int watchCount;
    String lastWatchedAt;

    /** Full SMB URL for opening in a local player, e.g. {@code smb://qnap2/AV/stars/Anissa%20Kate/old/video.mp4}. */
    String smbUrl;

    /** Screenshot URLs in seq order, e.g. {@code ["/api/av/screenshots/42/0", …]}. Empty list if none generated yet. */
    List<String> screenshotUrls;

    /** Canonical tag slugs applied to this video via 'av tags apply'. Empty list if none. */
    List<String> tags;
}
