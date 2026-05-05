package com.organizer3.avstars.repository;

import com.organizer3.avstars.model.AvVideoScreenshot;

import java.util.List;
import java.util.Map;

/**
 * Data access for {@link AvVideoScreenshot} records.
 */
public interface AvScreenshotRepository {

    /** Returns all screenshots for a video ordered by seq ascending. */
    List<AvVideoScreenshot> findByVideoId(long avVideoId);

    /**
     * Returns the minimum (first) seq number for each video that has screenshots.
     * Videos with no screenshots are absent from the result map.
     */
    Map<Long, Integer> findFirstSeqByVideoIds(List<Long> videoIds);

    /**
     * Returns the screenshot count for each video in the given list.
     * Videos with no screenshots are absent from the result map.
     */
    Map<Long, Integer> findCountsByVideoIds(List<Long> videoIds);

    /** Returns the number of screenshots stored for the given video. */
    int countByVideoId(long avVideoId);

    /** Inserts a new screenshot row. Silently replaces on (av_video_id, seq) conflict. */
    void insert(long avVideoId, int seq, String path);

    /** Deletes all screenshots for the given video (e.g. to regenerate them). */
    void deleteByVideoId(long avVideoId);

    /**
     * Deletes every screenshot row whose video belongs to the given actress.
     * Returns the count of rows deleted. Used by the per-actress reset flow.
     */
    int deleteByActressId(long avActressId);

    /**
     * For each actress id in the input, returns the number of her videos that have ≥1
     * screenshot row. Actresses with zero screenshots are present in the result map with
     * value 0. Used by the catalog list to render a per-actress progress bar.
     */
    Map<Long, Integer> countVideosWithScreenshotsByActresses(List<Long> actressIds);
}
