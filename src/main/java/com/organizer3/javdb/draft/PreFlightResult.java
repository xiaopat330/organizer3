package com.organizer3.javdb.draft;

import java.util.Collections;
import java.util.List;

/**
 * Result of a pre-flight validation check ({@link DraftPromotionService#preflight}).
 *
 * <p>A successful result has {@code ok=true} and an empty error list.
 * A failed result has {@code ok=false} and one or more structured error codes.
 *
 * <h3>Error codes</h3>
 * <ul>
 *   <li>{@code DRAFT_NOT_FOUND} — no draft exists for the given title id.</li>
 *   <li>{@code CAST_MODE_VIOLATION} — cast resolutions violate the mode rule (§5.1).</li>
 *   <li>{@code MISSING_ENGLISH_LAST_NAME} — a {@code create_new} slot has no last name.</li>
 *   <li>{@code UNRESOLVED_CAST_SLOT} — at least one slot is still {@code unresolved}.</li>
 *   <li>{@code UPSTREAM_CHANGED} — {@code draft_titles.upstream_changed=1}.</li>
 *   <li>{@code OPTIMISTIC_LOCK_CONFLICT} — caller token doesn't match current {@code updated_at}.</li>
 * </ul>
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §4.1.
 */
public record PreFlightResult(boolean ok, List<String> errors) {

    public static PreFlightResult success() {
        return new PreFlightResult(true, Collections.emptyList());
    }

    public static PreFlightResult failure(String... errorCodes) {
        return new PreFlightResult(false, List.of(errorCodes));
    }

    public static PreFlightResult failure(List<String> errorCodes) {
        return new PreFlightResult(false, Collections.unmodifiableList(errorCodes));
    }
}
