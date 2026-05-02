package com.organizer3.javdb.draft;

import lombok.Builder;
import lombok.Value;

/**
 * Domain record for a {@code draft_titles} row.
 *
 * <p>Represents a user-initiated enrichment work-in-progress for a single
 * canonical {@code titles} row. At most one active draft per title is enforced
 * by a unique index on {@code title_id}.
 *
 * <p>{@code updatedAt} doubles as the optimistic-lock token: callers must
 * supply the expected value when updating to detect concurrent modifications.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §2, §3, §12.4.
 */
@Value
@Builder(toBuilder = true)
public class DraftTitle {

    /** Surrogate PK; 0 / null before first insert. */
    long id;

    /** FK to {@code titles.id}. */
    long titleId;

    /** Snapshot of {@code titles.code} at draft creation time. */
    String code;

    String titleOriginal;
    String titleEnglish;
    String releaseDate;
    String notes;
    String grade;
    String gradeSource;

    /**
     * Set to {@code 1} by the sync hook when the canonical titles row is
     * touched while this draft is active. Signals the editor to show the
     * upstream-changed banner.
     */
    boolean upstreamChanged;

    /**
     * Last error message from a failed promote attempt; {@code null} on
     * a freshly populated draft.
     */
    String lastValidationError;

    String createdAt;

    /**
     * ISO-8601 timestamp; also serves as the optimistic-lock token.
     * Callers must pass this value to {@link DraftTitleRepository#update} and
     * {@link DraftTitleRepository#setUpstreamChanged}.
     */
    String updatedAt;
}
