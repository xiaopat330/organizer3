package com.organizer3.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.Collection;

/**
 * Maintains the {@code actress_companies} denormalization table, which precomputes
 * which companies each actress has appeared under.
 *
 * <p>Must be called whenever title_actresses or labels change.
 */
@Slf4j
@RequiredArgsConstructor
public class ActressCompaniesService {

    private final Jdbi jdbi;

    /** Recomputes company affiliations for a single actress. */
    public void recomputeForActress(long actressId) {
        jdbi.useHandle(h -> {
            h.createUpdate("DELETE FROM actress_companies WHERE actress_id = :id")
                    .bind("id", actressId)
                    .execute();
            h.createUpdate("""
                    INSERT OR IGNORE INTO actress_companies (actress_id, company)
                    SELECT DISTINCT :id, l.company
                    FROM title_actresses ta
                    JOIN titles t ON t.id = ta.title_id
                    JOIN labels l ON l.code = t.label
                    WHERE ta.actress_id = :id
                      AND t.label IS NOT NULL AND t.label != ''
                      AND l.company IS NOT NULL
                    """)
                    .bind("id", actressId)
                    .execute();
        });
    }

    /** Recomputes company affiliations for a batch of actresses (called at end of sync). */
    public void recomputeForActresses(Collection<Long> actressIds) {
        if (actressIds == null || actressIds.isEmpty()) return;
        log.debug("Recomputing actress_companies for {} actresses", actressIds.size());
        for (long id : actressIds) {
            recomputeForActress(id);
        }
    }

    /** Full recompute — called after labels data is reseeded. */
    public void recomputeAll() {
        log.info("Recomputing all actress_companies");
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM actress_companies");
            h.execute("""
                    INSERT OR IGNORE INTO actress_companies (actress_id, company)
                    SELECT DISTINCT ta.actress_id, l.company
                    FROM title_actresses ta
                    JOIN titles t ON t.id = ta.title_id
                    JOIN labels l ON l.code = t.label
                    WHERE t.label IS NOT NULL AND t.label != ''
                      AND l.company IS NOT NULL
                    """);
        });
        log.info("actress_companies recompute complete");
    }
}
