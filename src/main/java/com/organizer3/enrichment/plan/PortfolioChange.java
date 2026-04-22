package com.organizer3.enrichment.plan;

import java.util.List;

/**
 * Planned change for one portfolio entry. A portfolio entry always targets one title (by code):
 * either a new stub title is created, or an existing title's enrichment fields + tags are updated.
 *
 * <p>For enrichment, only the diffing subset is emitted — fields whose YAML value matches the
 * current DB value are dropped. Tags are reported as three lists: added, removed, unchanged;
 * the third is for context only (helps the UI show "no change" vs "big change").
 */
public sealed interface PortfolioChange {

    String code();
    String kind();

    record CreateTitle(String code,
                       String titleOriginal,
                       String titleEnglish,
                       String releaseDate,
                       String notes,
                       String grade,
                       List<String> tags) implements PortfolioChange {
        public CreateTitle { tags = List.copyOf(tags); }
        @Override public String kind() { return "create"; }
    }

    record EnrichTitle(long titleId,
                       String code,
                       List<FieldChange> fields,
                       List<String> tagsAdded,
                       List<String> tagsRemoved,
                       List<String> tagsUnchanged) implements PortfolioChange {
        public EnrichTitle {
            fields        = List.copyOf(fields);
            tagsAdded     = List.copyOf(tagsAdded);
            tagsRemoved   = List.copyOf(tagsRemoved);
            tagsUnchanged = List.copyOf(tagsUnchanged);
        }
        @Override public String kind() { return "enrich"; }
        /** Returns true if nothing actually changes for this title (both fields and tags no-op). */
        public boolean isNoop() {
            return fields.isEmpty() && tagsAdded.isEmpty() && tagsRemoved.isEmpty();
        }
    }
}
