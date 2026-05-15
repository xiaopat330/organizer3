package com.organizer3.notes;

import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

/**
 * JDBI-backed implementation of {@link NoteService.EntityResolver}.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li><b>ACTRESS</b> — {@code entity_id} is the numeric actress id (as a string).
 *       Canonical if found in {@code actresses}. Draft check: actress drafts use
 *       {@code javdb_slug} (TEXT) as PK — they share no namespace with numeric ids,
 *       so there is no reachable "draft-only" path via a numeric id; any miss is
 *       simply {@code NOT_FOUND}.</li>
 *   <li><b>TITLE</b> — {@code entity_id} is the title code (e.g. "ABP-123").
 *       Canonical if found in {@code titles} by {@code code}. Draft titles always
 *       have a corresponding canonical {@code titles} row (FK constraint), so there
 *       is likewise no reachable "draft-only" path; any miss is {@code NOT_FOUND}.</li>
 * </ul>
 *
 * <p>The draft-rejection path is kept explicit so the contract is clear if the schema
 * ever introduces a pre-canonical draft concept with overlapping identifiers.
 */
@RequiredArgsConstructor
public class JdbiEntityResolver implements NoteService.EntityResolver {

    private final Jdbi jdbi;

    @Override
    public Resolution resolve(EntityType type, String id) {
        return switch (type) {
            case ACTRESS -> resolveActress(id);
            case TITLE   -> resolveTitle(id);
        };
    }

    // ── Actress ───────────────────────────────────────────────────────────────

    private Resolution resolveActress(String id) {
        // entity_id for actress is the numeric DB id
        long numericId;
        try {
            numericId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            // Non-numeric id cannot match any canonical actress
            return Resolution.NOT_FOUND;
        }

        boolean canonical = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE id = :id")
                        .bind("id", numericId)
                        .mapTo(Integer.class)
                        .one() > 0);

        return canonical ? Resolution.CANONICAL : Resolution.NOT_FOUND;
    }

    // ── Title ─────────────────────────────────────────────────────────────────

    private Resolution resolveTitle(String code) {
        boolean canonical = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles WHERE code = :code")
                        .bind("code", code)
                        .mapTo(Integer.class)
                        .one() > 0);

        return canonical ? Resolution.CANONICAL : Resolution.NOT_FOUND;
    }
}
