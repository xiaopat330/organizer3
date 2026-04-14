package com.organizer3.avstars.repository;

import com.organizer3.avstars.model.AvVideoScreenshot;

import java.util.List;

/**
 * Data access for {@link AvVideoScreenshot} records.
 */
public interface AvScreenshotRepository {

    /** Returns all screenshots for a video ordered by seq ascending. */
    List<AvVideoScreenshot> findByVideoId(long avVideoId);

    /** Returns the number of screenshots stored for the given video. */
    int countByVideoId(long avVideoId);

    /** Inserts a new screenshot row. Silently replaces on (av_video_id, seq) conflict. */
    void insert(long avVideoId, int seq, String path);

    /** Deletes all screenshots for the given video (e.g. to regenerate them). */
    void deleteByVideoId(long avVideoId);
}
