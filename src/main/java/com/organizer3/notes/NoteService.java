package com.organizer3.notes;

import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Business logic for user note management.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Trim and NFC-normalize body text.</li>
 *   <li>Enforce the 280-char cap (server defense-in-depth; UI also caps).</li>
 *   <li>Treat empty/whitespace body as a delete (no zombie rows).</li>
 *   <li>Validate that entity ids resolve to <em>canonical</em> rows only —
 *       draft entities are rejected with {@link UpsertResult#DRAFT_REJECTED}.</li>
 * </ul>
 *
 * <p>Canonical existence is checked through the injected {@link EntityResolver}
 * so this class stays mockable in unit tests without a real database.
 */
@Slf4j
public class NoteService {

    /** Maximum allowed note body length after trim + NFC normalization. */
    public static final int MAX_BODY_LENGTH = 280;

    private final NoteRepository repo;
    private final EntityResolver resolver;

    public NoteService(NoteRepository repo, EntityResolver resolver) {
        this.repo = repo;
        this.resolver = resolver;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the note for the given entity, or empty if none exists. */
    public Optional<Note> find(EntityType type, String id) {
        return repo.find(type, id);
    }

    /**
     * Returns notes for every id in {@code ids} that has a note.
     * Ids without notes are omitted from the returned map.
     */
    public Map<String, Note> findBatch(EntityType type, Collection<String> ids) {
        return repo.findAllForType(type, ids);
    }

    /**
     * Upsert a note for the given entity.
     *
     * <p>If {@code body} is null, empty, or whitespace after trimming, the note
     * is deleted and {@link UpsertResult#DELETED} is returned.
     *
     * @return a {@link UpsertResult} describing the outcome
     */
    public UpsertResult upsert(EntityType type, String id, String rawBody) {
        // Normalize: trim then NFC
        String body = rawBody == null ? "" : Normalizer.normalize(rawBody.trim(), Normalizer.Form.NFC);

        // Empty body → delete (no zombie rows)
        if (body.isEmpty()) {
            repo.delete(type, id);
            return UpsertResult.DELETED;
        }

        // Length cap
        if (body.length() > MAX_BODY_LENGTH) {
            return UpsertResult.TOO_LONG;
        }

        // Resolve entity — check canonical existence and draft status
        EntityResolver.Resolution resolution = resolver.resolve(type, id);
        return switch (resolution) {
            case CANONICAL -> {
                repo.upsert(type, id, body);
                Note note = repo.find(type, id).orElseThrow(() ->
                        new IllegalStateException("Note not found immediately after upsert: " + type + "/" + id));
                yield new UpsertResult.Ok(note);
            }
            case DRAFT_ONLY -> UpsertResult.DRAFT_REJECTED;
            case NOT_FOUND  -> UpsertResult.NOT_FOUND;
        };
    }

    /**
     * Delete the note for the given entity. No-op if none exists. Does not
     * validate entity existence — DELETE is always safe.
     */
    public void delete(EntityType type, String id) {
        repo.delete(type, id);
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Result of {@link #upsert}. Callers (e.g. {@code NoteRoutes}) switch
     * on the type to produce the appropriate HTTP status code.
     *
     * <p>Singleton results (no payload) are exposed as constants on this interface
     * for ergonomic comparisons: {@code result == UpsertResult.DELETED}.
     */
    public interface UpsertResult {

        /** Successful upsert; carries the persisted note. */
        record Ok(Note note) implements UpsertResult {}

        // Singleton sentinel results

        UpsertResult DELETED        = new Sentinel("DELETED");
        UpsertResult TOO_LONG       = new Sentinel("TOO_LONG");
        UpsertResult DRAFT_REJECTED = new Sentinel("DRAFT_REJECTED");
        UpsertResult NOT_FOUND      = new Sentinel("NOT_FOUND");

        /** Internal sentinel — not exposed beyond the interface constants above. */
        final class Sentinel implements UpsertResult {
            private final String name;
            private Sentinel(String name) { this.name = name; }
            @Override public String toString() { return "UpsertResult." + name; }
        }
    }

    // Convenience re-exports for callers that import NoteService directly
    public static final UpsertResult DELETED        = UpsertResult.DELETED;
    public static final UpsertResult TOO_LONG       = UpsertResult.TOO_LONG;
    public static final UpsertResult DRAFT_REJECTED = UpsertResult.DRAFT_REJECTED;
    public static final UpsertResult NOT_FOUND      = UpsertResult.NOT_FOUND;

    // ── Entity resolver contract ──────────────────────────────────────────────

    /**
     * Abstraction over "does this entity id exist, and is it canonical?".
     * Implemented against the live DB; replaceable with a mock in tests.
     */
    public interface EntityResolver {

        enum Resolution { CANONICAL, DRAFT_ONLY, NOT_FOUND }

        Resolution resolve(EntityType type, String id);
    }
}
