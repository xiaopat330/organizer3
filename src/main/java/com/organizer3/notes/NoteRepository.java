package com.organizer3.notes;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence operations for {@link Note} records.
 *
 * <p>The batch {@link #findAllForType(EntityType, Collection)} is the hot path:
 * card grids and detail-page hydrators load notes for the visible page in one
 * query rather than N+1 fetches.
 */
public interface NoteRepository {

    /** Returns the note for the given entity, or empty if none exists. */
    Optional<Note> find(EntityType type, String id);

    /**
     * Returns notes for every id in {@code ids} that has a note, keyed by entity id.
     * Ids without notes are omitted from the map.
     */
    Map<String, Note> findAllForType(EntityType type, Collection<String> ids);

    /**
     * Returns every note in the table, regardless of entity type or orphan status.
     * Used by backup export to capture all user data including orphan rows.
     */
    List<Note> findAll();

    /** Insert or update the note body for the given entity. */
    void upsert(EntityType type, String id, String body);

    /**
     * Insert or update a note with explicit timestamps.
     *
     * <p>Used exclusively by backup restore to preserve the original {@code createdAt}
     * and {@code updatedAt} values. The standard {@link #upsert} always stamps
     * {@code Instant.now()}, so a dedicated method is required for timestamp-faithful
     * round-trips.
     *
     * <p>This path bypasses {@code NoteService} validation intentionally — backup
     * restore is a trusted operation and must preserve orphan rows whose entity no
     * longer exists in the canonical tables.
     */
    void restoreNote(EntityType type, String id, String body, long createdAt, long updatedAt);

    /** Delete the note for the given entity. No-op if none exists. */
    void delete(EntityType type, String id);

    /**
     * Delete notes whose {@code entity_id} no longer exists in the corresponding
     * canonical table ({@code actresses} or {@code titles}).
     *
     * @return the number of orphan rows deleted
     */
    int sweepOrphans();
}
