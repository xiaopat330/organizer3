package com.organizer3.javdb.draft;

import lombok.Value;

/**
 * Domain record for a {@code draft_title_actresses} row.
 *
 * <p>Represents one cast-slot resolution for a draft title.
 * Valid resolution values (per spec §5.2):
 * <ul>
 *   <li>{@code pick} — link to existing actress; requires
 *       {@code draft_actresses.link_to_existing_id} set.</li>
 *   <li>{@code create_new} — create a new actress on promote; requires
 *       {@code draft_actresses.english_last_name} non-empty.</li>
 *   <li>{@code skip} — discard this stage_name; available in multi-actress mode only.</li>
 *   <li>{@code sentinel:<actress_id>} — replace cast with a sentinel actress.</li>
 *   <li>{@code unresolved} — populator was unable to auto-link; must be resolved before
 *       Validate.</li>
 * </ul>
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §5.2 and §12.4.
 */
@Value
public class DraftTitleActress {

    long draftTitleId;

    /** References {@code draft_actresses.javdb_slug}. */
    String javdbSlug;

    /**
     * Cast-slot resolution string. One of: {@code pick}, {@code create_new},
     * {@code skip}, {@code sentinel:<id>}, {@code unresolved}.
     */
    String resolution;
}
