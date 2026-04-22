package com.organizer3.enrichment.plan;

import java.util.List;

/**
 * Read-only plan describing exactly what would change if the YAML were applied. Produced by
 * {@code ActressYamlLoader.plan(slug)}; consumed by the preview endpoint for the visualize UI and
 * by {@code apply(plan)} on the write path.
 *
 * <p>Summary counts are pre-computed at the Java layer so the UI doesn't have to re-derive them
 * from the nested lists.
 */
public record ActressYamlPlan(
        String slug,
        ActressChange actressChange,
        List<PortfolioChange> portfolioChanges,
        List<String> unresolvedNotes,
        Summary summary) {

    public ActressYamlPlan {
        portfolioChanges = List.copyOf(portfolioChanges);
        unresolvedNotes  = List.copyOf(unresolvedNotes);
    }

    /**
     * Aggregate counts for the UI banner. {@code actressChanged} is 1 for Update with changes or
     * Create; 0 for Update with zero field deltas (pure no-op).
     */
    public record Summary(int actressChanged,
                          int titlesToCreate,
                          int titlesToEnrich,
                          int titlesNoop,
                          int fieldChanges,
                          int tagsAdded,
                          int tagsRemoved) {}
}
