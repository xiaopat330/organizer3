package com.organizer3.javdb.draft;

import lombok.Builder;
import lombok.Value;

/**
 * Domain record for a {@code draft_actresses} row.
 *
 * <p>Keyed by {@code javdbSlug}; shared globally across all active drafts.
 * Ref-counted via {@code draft_title_actresses}; orphan rows are reaped by
 * the GC sweep ({@link DraftActressRepository#reapOrphans}).
 *
 * <p>{@code updatedAt} is the optimistic-lock token for future update paths.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §2 and §12.4.
 */
@Value
@Builder(toBuilder = true)
public class DraftActress {

    /** PK — javdb actress slug (e.g. {@code "abc12"}). */
    String javdbSlug;

    /** Stage name as returned by javdb; immutable once first written. */
    String stageName;

    /** User-supplied; nullable for mononyms. */
    String englishFirstName;

    /** User-supplied; required when resolution = {@code create_new}. */
    String englishLastName;

    /**
     * Set when the user picks "link to existing" — references {@code actresses.id}.
     * {@code null} otherwise.
     */
    Long linkToExistingId;

    String createdAt;

    /** ISO-8601 timestamp; also serves as optimistic-lock token. */
    String updatedAt;

    String lastValidationError;
}
