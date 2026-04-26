package com.organizer3.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.Collection;

/**
 * Maintains the {@code title_effective_tags} denormalization table, which precomputes the
 * merged tag set (direct title_tags + inherited label_tags + enrichment-derived via
 * {@code enrichment_tag_definitions.curated_alias}) for each title.
 *
 * <p>Must be called whenever any source changes:
 * <ul>
 *   <li>{@code title_tags} edits — direct rows</li>
 *   <li>{@code label_tags} edits — label-inherited rows</li>
 *   <li>Enrichment row inserted/replaced/deleted, or {@code curated_alias} change —
 *       enrichment-derived rows</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class TitleEffectiveTagsService {

    private final Jdbi jdbi;

    /** Recomputes the effective tags for a single title across all three sources. */
    public void recomputeForTitle(long titleId) {
        jdbi.useHandle(h -> {
            h.createUpdate("DELETE FROM title_effective_tags WHERE title_id = :id")
                    .bind("id", titleId)
                    .execute();
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT :id, tag, 'direct' FROM title_tags WHERE title_id = :id
                    """)
                    .bind("id", titleId)
                    .execute();
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT :id, lt.tag, 'label'
                    FROM label_tags lt
                    WHERE lt.label_code = (SELECT label FROM titles WHERE id = :id)
                      AND lt.label_code IS NOT NULL AND lt.label_code != ''
                    """)
                    .bind("id", titleId)
                    .execute();
            // Enrichment-derived rows: each enrichment tag whose curated_alias names a
            // real curated tag flows in here. The unique key on (title_id, tag) means we
            // never double-insert if the user has also tagged the title manually.
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT :id, etd.curated_alias, 'enrichment'
                    FROM title_enrichment_tags tet
                    JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                    WHERE tet.title_id = :id
                      AND etd.curated_alias IS NOT NULL
                      AND etd.curated_alias IN (SELECT name FROM tags)
                    """)
                    .bind("id", titleId)
                    .execute();
        });
    }

    /** Recomputes effective tags for a batch of titles (called at end of sync). */
    public void recomputeForTitles(Collection<Long> titleIds) {
        if (titleIds == null || titleIds.isEmpty()) return;
        log.debug("Recomputing effective tags for {} titles", titleIds.size());
        for (long id : titleIds) {
            recomputeForTitle(id);
        }
    }

    /**
     * Full recompute — called after {@code label_tags} is reseeded, after the v26 migration
     * backfill, or whenever the enrichment alias bridge changes en masse.
     *
     * <p>Implemented as three bulk {@code INSERT … SELECT} statements rather than per-title
     * to scale cleanly to full library size.
     */
    public void recomputeAll() {
        log.info("Recomputing all title_effective_tags");
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM title_effective_tags");
            h.execute("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT title_id, tag, 'direct' FROM title_tags
                    """);
            h.execute("""
                    INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                    SELECT t.id, lt.tag, 'label'
                    FROM titles t
                    JOIN label_tags lt ON lt.label_code = t.label
                    WHERE t.label IS NOT NULL AND t.label != ''
                    """);
            // Enrichment-derived. See recomputeForTitle for the same query scoped to one title.
            // Skipped silently if the v25 enrichment tables aren't present yet.
            int hasEtags = h.createQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='title_enrichment_tags'")
                    .mapTo(Integer.class).one();
            if (hasEtags > 0) {
                h.execute("""
                        INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                        SELECT tet.title_id, etd.curated_alias, 'enrichment'
                        FROM title_enrichment_tags tet
                        JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                        WHERE etd.curated_alias IS NOT NULL
                          AND etd.curated_alias IN (SELECT name FROM tags)
                        """);
            }
        });
        log.info("title_effective_tags recompute complete");
    }
}
