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

    /** Eligibility codes returned by {@link #eligibility(long)}. */
    public static final String ELIGIBLE         = "eligible";
    public static final String BELOW_THRESHOLD  = "below_threshold";
    public static final String SENTINEL         = "sentinel";

    /**
     * Returns {@code true} when the actress passes all three eligibility checks
     * and a profile fetch should be auto-chained.
     *
     * @param actressId the actress to check; 0 means "no actress" and always returns false
     */
    public boolean shouldChainProfile(long actressId) {
        return ELIGIBLE.equals(eligibility(actressId));
    }

    /**
     * Returns the eligibility code for a row in the Titles / Collections tab. Distinguishes
     * sentinel actresses (red) from real-but-below-threshold (grey) so the UI can convey the
     * gate decision precisely.
     *
     * <p>{@code actressId == 0} is treated as below-threshold (no actress to chain).
     */
    public String eligibility(long actressId) {
        if (actressId == 0) return BELOW_THRESHOLD;
        int minTitles = config.profileChainMinTitlesOrDefault();

        return jdbi.withHandle(h -> {
            var row = h.createQuery("""
                    SELECT a.is_sentinel AS sentinel,
                           (SELECT COUNT(*) FROM title_actresses ta WHERE ta.actress_id = a.id) AS cnt
                    FROM actresses a WHERE a.id = :actressId
                    """)
                    .bind("actressId", actressId)
                    .mapToMap()
                    .findOne()
                    .orElse(null);
            if (row == null) return BELOW_THRESHOLD;
            int sentinel = ((Number) row.get("sentinel")).intValue();
            if (sentinel != 0) return SENTINEL;
            int count = ((Number) row.get("cnt")).intValue();
            return count >= minTitles ? ELIGIBLE : BELOW_THRESHOLD;
        });
    }
}
