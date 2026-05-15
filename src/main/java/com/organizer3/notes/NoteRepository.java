package com.organizer3.notes;

import java.util.Collection;
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

    /** Insert or update the note body for the given entity. */
    void upsert(EntityType type, String id, String body);

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
