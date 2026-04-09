package com.organizer3.repository;

import com.organizer3.model.WatchHistory;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence operations for watch history records.
 */
public interface WatchHistoryRepository {

    /** Record a watch event for a title. */
    WatchHistory record(String titleCode, LocalDateTime watchedAt);

    /** Get the full watch history, most recent first. */
    List<WatchHistory> findAll(int limit);

    /** Get watch history for a specific title code. */
    List<WatchHistory> findByTitleCode(String titleCode);

    /** Get the most recent watch time for a title, if any. */
    Optional<LocalDateTime> lastWatchedAt(String titleCode);

    /** Delete all watch history for a title code. */
    void deleteByTitleCode(String titleCode);

    /** Batch lookup: returns titleCode → (most recent watchedAt, watch count) for all codes that have history. */
    Map<String, WatchStats> findWatchStatsBatch(Collection<String> titleCodes);

    /** Aggregated watch statistics for a single title. */
    record WatchStats(LocalDateTime lastWatchedAt, int count) {}
}
