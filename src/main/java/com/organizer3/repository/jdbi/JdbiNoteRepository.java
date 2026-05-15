package com.organizer3.repository.jdbi;

import com.organizer3.notes.EntityType;
import com.organizer3.notes.Note;
import com.organizer3.notes.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * JDBI-backed implementation of {@link NoteRepository}.
 *
 * <p>The {@code notes} table uses {@code (entity_type, entity_id)} as its primary key.
 * {@code created_at} is set on first insert and never updated; {@code updated_at}
 * is stamped on every upsert.
 */
@RequiredArgsConstructor
public class JdbiNoteRepository implements NoteRepository {

    private static final RowMapper<Note> MAPPER = (rs, ctx) -> new Note(
            EntityType.fromWireValue(rs.getString("entity_type")),
            rs.getString("entity_id"),
            rs.getString("body"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    private final Jdbi jdbi;

    @Override
    public Optional<Note> find(EntityType type, String id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM notes WHERE entity_type = :type AND entity_id = :id")
                        .bind("type", type.wireValue())
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst());
    }

    @Override
    public Map<String, Note> findAllForType(EntityType type, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> idList = new ArrayList<>(ids);
        String placeholders = "?,".repeat(idList.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);

        String sql = "SELECT * FROM notes WHERE entity_type = ? AND entity_id IN (" + placeholders + ")";

        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql).bind(0, type.wireValue());
            for (int i = 0; i < idList.size(); i++) {
                query = query.bind(i + 1, idList.get(i));
            }
            List<Note> notes = query.map(MAPPER).list();
            Map<String, Note> result = new HashMap<>();
            for (Note note : notes) {
                result.put(note.entityId(), note);
            }
            return result;
        });
    }

    @Override
    public List<Note> findAll() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM notes ORDER BY entity_type, entity_id")
                        .map(MAPPER)
                        .list());
    }

    @Override
    public void upsert(EntityType type, String id, String body) {
        long now = Instant.now().toEpochMilli();
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO notes (entity_type, entity_id, body, created_at, updated_at)
                        VALUES (:type, :id, :body, :now, :now)
                        ON CONFLICT(entity_type, entity_id)
                        DO UPDATE SET body = excluded.body, updated_at = excluded.updated_at
                        """)
                .bind("type", type.wireValue())
                .bind("id", id)
                .bind("body", body)
                .bind("now", now)
                .execute());
    }

    @Override
    public void restoreNote(EntityType type, String id, String body, long createdAt, long updatedAt) {
        jdbi.useHandle(h -> h.createUpdate("""
                        INSERT INTO notes (entity_type, entity_id, body, created_at, updated_at)
                        VALUES (:type, :id, :body, :createdAt, :updatedAt)
                        ON CONFLICT(entity_type, entity_id)
                        DO UPDATE SET body = excluded.body,
                                      created_at = excluded.created_at,
                                      updated_at = excluded.updated_at
                        """)
                .bind("type", type.wireValue())
                .bind("id", id)
                .bind("body", body)
                .bind("createdAt", createdAt)
                .bind("updatedAt", updatedAt)
                .execute());
    }

    @Override
    public void delete(EntityType type, String id) {
        jdbi.useHandle(h -> h.createUpdate(
                        "DELETE FROM notes WHERE entity_type = :type AND entity_id = :id")
                .bind("type", type.wireValue())
                .bind("id", id)
                .execute());
    }

    @Override
    public int sweepOrphans() {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        DELETE FROM notes
                        WHERE (entity_type = 'actress'
                               AND entity_id NOT IN (SELECT CAST(id AS TEXT) FROM actresses))
                           OR (entity_type = 'title'
                               AND entity_id NOT IN (SELECT code FROM titles))
                        """)
                        .execute());
    }
}
