package com.organizer3.repository;

import java.util.List;

/**
 * Persistence operations for title tags.
 *
 * <p>Tags are stored in the {@code title_tags} table and populated from curated YAML
 * research data via the {@code load actress} command. They enable cross-actress queries
 * like "find all titles tagged 'femdom'" and per-actress filtering.
 */
public interface TitleTagRepository {

    /** Return all tags for a title, sorted alphabetically. */
    List<String> findTagsForTitle(long titleId);

    /**
     * Replace all tags for a title atomically.
     * Deletes existing tags then inserts the new set in one transaction.
     */
    void replaceTagsForTitle(long titleId, List<String> tags);

    /** Return IDs of all titles that have the given tag. */
    List<Long> findTitleIdsByTag(String tag);

    /** Return IDs of titles for a specific actress that have the given tag. */
    List<Long> findTitleIdsByTagAndActress(String tag, long actressId);
}
