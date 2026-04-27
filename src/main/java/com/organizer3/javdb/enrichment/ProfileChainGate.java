package com.organizer3.javdb.enrichment;

import com.organizer3.javdb.JavdbConfig;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

/**
 * Eligibility gate for auto-chaining a {@code fetch_actress_profile} job after a title fetch
 * in the title-driven enrichment flows (recent, pool, collection).
 *
 * <p>Not applied to actress-driven jobs — those always chain unconditionally.
 *
 * <p>Three checks (all must pass):
 * <ol>
 *   <li>Actress exists in the DB.</li>
 *   <li>Actress is not a sentinel ({@code is_sentinel = 0}).
 *       Sentinels are placeholder names (Various, Unknown, Amateur) and are never real performers.</li>
 *   <li>Actress has at least {@code javdb.profileChainMinTitles} titles credited
 *       across all title_actresses rows (collections appearances count).</li>
 * </ol>
 */
@RequiredArgsConstructor
public class ProfileChainGate {

    private final Jdbi jdbi;
    private final JavdbConfig config;

    /**
     * Returns {@code true} when the actress passes all three eligibility checks
     * and a profile fetch should be auto-chained.
     *
     * @param actressId the actress to check; 0 means "no actress" and always returns false
     */
    public boolean shouldChainProfile(long actressId) {
        if (actressId == 0) return false;

        int minTitles = config.profileChainMinTitlesOrDefault();

        return jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) > 0
                FROM actresses a
                WHERE a.id = :actressId
                  AND a.is_sentinel = 0
                  AND (SELECT COUNT(*) FROM title_actresses ta WHERE ta.actress_id = :actressId) >= :minTitles
                """)
                .bind("actressId", actressId)
                .bind("minTitles", minTitles)
                .mapTo(Boolean.class)
                .one());
    }
}
