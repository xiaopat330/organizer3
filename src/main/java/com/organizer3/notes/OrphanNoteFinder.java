package com.organizer3.notes;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Health-sweep service that surfaces and prunes notes whose entity has been deleted.
 *
 * <p>An orphan note is one whose {@code entity_id} no longer exists in the corresponding
 * canonical table — {@code actresses} (for type=actress) or {@code titles} (for type=title).
 *
 * <p>Mirrors the {@code find_stale_locations} / {@code prune_stale_locations} pattern:
 * no auto-cleanup at startup; the user explicitly calls the MCP tools.
 *
 * <p>The {@link #pruneAll()} DELETE exactly matches the {@link #findAll()} SELECT predicate
 * so that a dry-run count can never disagree with the actual prune.
 */
@RequiredArgsConstructor
public class OrphanNoteFinder {

    private static final String ORPHAN_PREDICATE = """
            (entity_type = 'actress'
             AND entity_id NOT IN (SELECT CAST(id AS TEXT) FROM actresses))
          OR (entity_type = 'title'
              AND entity_id NOT IN (SELECT code FROM titles))
            """;

    private final Jdbi jdbi;

    /**
     * Returns all notes whose backing entity no longer exists.
     */
    public List<OrphanNote> findAll() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT entity_type, entity_id, body, updated_at FROM notes WHERE " + ORPHAN_PREDICATE + " ORDER BY entity_type, entity_id")
                        .map((rs, ctx) -> new OrphanNote(
                                EntityType.fromWireValue(rs.getString("entity_type")),
                                rs.getString("entity_id"),
                                rs.getString("body"),
                                rs.getLong("updated_at")))
                        .list());
    }

    /**
     * Deletes all orphan notes.
     *
     * @return the number of rows deleted
     */
    public int pruneAll() {
        return jdbi.withHandle(h ->
                h.createUpdate("DELETE FROM notes WHERE " + ORPHAN_PREDICATE)
                        .execute());
    }

    /**
     * A note whose entity no longer exists in the canonical table.
     *
     * @param entityType the type of entity the note was attached to
     * @param entityId   the id of the now-deleted entity
     * @param body       the note text (preserved for user review before pruning)
     * @param updatedAt  epoch millis of the last edit
     */
    public record OrphanNote(
            EntityType entityType,
            String entityId,
            String body,
            long updatedAt
    ) {}
}
