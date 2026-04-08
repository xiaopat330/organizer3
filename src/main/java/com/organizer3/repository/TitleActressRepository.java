package com.organizer3.repository;

import java.util.List;

/**
 * Persistence operations for the {@code title_actresses} junction table.
 *
 * <p>Records the casting relationship between titles and actresses — which actresses
 * appear in which titles. This is separate from {@code titles.actress_id}, which
 * represents the filing actress (the actress folder a title lives under on conventional
 * volumes). For multi-actress titles (e.g., collections), {@code actress_id} is null
 * while the junction table holds all cast members.
 *
 * <p>All writes use {@code INSERT OR IGNORE} so re-syncing a volume is idempotent.
 */
public interface TitleActressRepository {

    /** Links one actress to a title. No-op if the relationship already exists. */
    void link(long titleId, long actressId);

    /** Links all actresses to a title in a single transaction. No-ops for existing rows. */
    void linkAll(long titleId, List<Long> actressIds);

    /** Returns all actress ids linked to the given title. */
    List<Long> findActressIdsByTitle(long titleId);

    /**
     * Removes junction rows whose title no longer exists in the {@code titles} table.
     * Called after {@code TitleRepository.deleteOrphaned()} to keep the junction table clean.
     */
    void deleteOrphaned();
}
