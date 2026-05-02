package com.organizer3.repository;

import com.organizer3.model.TitlePathHistoryEntry;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for {@code title_path_history}.
 *
 * <p>Records the history of volume-relative paths that a title has ever occupied.
 * Used by {@link com.organizer3.sync.SyncIdentityMatcher} as a last-resort fallback
 * layer: when code-based matching misses, the history is consulted to see if the
 * folder path was previously associated with a known title.
 *
 * <p>Rows intentionally have no FK on {@code title_id} — they survive title deletion
 * so a re-add can recover the prior identity.
 */
public interface TitlePathHistoryRepository {

    /**
     * Upsert a path-history row for {@code (volumeId, partitionId, path)}.
     *
     * <p>If no row exists for the composite key, inserts with {@code first_seen_at = nowIso}
     * and {@code last_seen_at = nowIso}. If a row already exists, bumps {@code last_seen_at}
     * to {@code nowIso} while preserving the original {@code first_seen_at}.
     */
    void recordPath(long titleId, String volumeId, String partitionId, String path, String nowIso);

    /**
     * Returns the most-recently-seen {@code title_id} associated with
     * {@code (volumeId, partitionId, path)}, or empty if no history row exists.
     *
     * <p>Only returns a value if the {@code title_id} still exists in the {@code titles} table;
     * stale rows pointing at deleted titles are skipped.
     */
    Optional<Long> findByPath(String volumeId, String partitionId, String path);

    /**
     * Returns all history rows for the given {@code titleId}, ordered by {@code last_seen_at DESC}.
     */
    List<TitlePathHistoryEntry> listForTitle(long titleId);
}
