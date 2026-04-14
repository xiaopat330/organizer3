package com.organizer3.avstars.repository;

import java.util.List;
import java.util.Map;

public interface AvVideoTagRepository {
    /**
     * Returns a map of videoId → list of tag slugs for the given video IDs.
     * Video IDs with no tags are omitted from the map.
     */
    Map<Long, List<String>> findTagSlugsByVideoIds(List<Long> videoIds);

    /**
     * Returns the top tag slugs for an actress, ordered by frequency descending.
     * Useful for the actress summary card on the index grid.
     */
    List<String> findTopTagSlugsForActress(long avActressId, int limit);

    void insertVideoTag(long avVideoId, String tagSlug, String source);

    void deleteByVideoId(long avVideoId);

    void deleteByVideoIdAndSource(long avVideoId, String source);

    /** Returns tags_json strings for all videos (null entries included). Used by 'av tags dump'. */
    List<String> getAllTagsJson();

    /** Returns [videoId, tags_json] pairs for all videos that have tags_json set. Used by 'av tags apply'. */
    List<String[]> getAllVideoIdAndTagsJson();
}
