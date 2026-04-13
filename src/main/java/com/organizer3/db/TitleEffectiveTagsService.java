package com.organizer3.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.Collection;

/**
 * Maintains the {@code title_effective_tags} denormalization table, which precomputes the
 * merged tag set (direct title_tags + inherited label_tags) for each title.
 *
 * <p>Must be called whenever title_tags or label_tags change.
 */
@Slf4j
@RequiredArgsConstructor
public class TitleEffectiveTagsService {

    private final Jdbi jdbi;

    /** Recomputes the effective tags for a single title. */
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

    /** Full recompute — called after label_tags is reseeded. */
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
        });
        log.info("title_effective_tags recompute complete");
    }
}
