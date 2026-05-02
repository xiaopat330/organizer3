package com.organizer3.javdb.draft;

import lombok.Builder;
import lombok.Value;

/**
 * Domain record for a {@code draft_title_javdb_enrichment} row.
 *
 * <p>Mirrors the columns of {@code title_javdb_enrichment} that are relevant
 * to Draft Mode. Raw javdb tags are stored here and resolved into
 * {@code title_enrichment_tags} only at promotion time (spec §6).
 *
 * <p>{@code updatedAt} is the optimistic-lock token. See spec §4.4.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §2 and §12.4.
 */
@Value
@Builder(toBuilder = true)
public class DraftEnrichment {

    /** FK to {@code draft_titles.id}; also serves as the PK. */
    long draftTitleId;

    String javdbSlug;

    /** JSON-serialized cast list from javdb; verbatim, never trimmed. */
    String castJson;

    String maker;
    String series;
    String coverUrl;

    /** Raw javdb tags JSON; resolved against alias map at promotion. */
    String tagsJson;

    Double ratingAvg;
    Integer ratingCount;

    /**
     * Provenance of the slug resolution: {@code auto_enriched} or
     * {@code manual_picker}.
     */
    String resolverSource;

    /** ISO-8601 timestamp; also serves as optimistic-lock token. */
    String updatedAt;
}
