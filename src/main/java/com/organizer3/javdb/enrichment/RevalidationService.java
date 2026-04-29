package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Map;

/**
 * Pure-SQL enrichment re-validation.
 *
 * <p>Checks each title's current enrichment slug against the per-actress filmography cache
 * ({@code javdb_actress_filmography_entry}). No HTTP calls are made — the verdict is derived
 * entirely from cached data already in the database.
 *
 * <p>Verdict rules (per title):
 * <ul>
 *   <li><b>CONFIRMED</b> — at least one non-sentinel linked actress has a filmography entry for
 *       the title's product code, and that entry's slug matches the enrichment slug.</li>
 *   <li><b>REJECTED</b> — no actress confirms, but at least one actress has a filmography entry
 *       for the product code with a <em>different</em> slug.</li>
 *   <li><b>NO_SIGNAL</b> — no linked actress has any filmography entry for the product code
 *       (e.g. actress not in staging, filmography not fetched, or title absent from her filmography).</li>
 * </ul>
 *
 * <p>On CONFIRMED: confidence → HIGH.
 * On REJECTED: confidence → LOW; if prior confidence was HIGH, an open entry is also written
 * to {@code enrichment_review_queue} (reason {@code no_match}).
 * On NO_SIGNAL: confidence unchanged.
 * In all cases {@code last_revalidated_at} is stamped.
 */
@Slf4j
@RequiredArgsConstructor
public class RevalidationService {

    private final Jdbi jdbi;

    public enum Verdict { CONFIRMED, REJECTED, NO_SIGNAL }

    public record RevalidationSummary(int confirmed, int rejected, int noSignal, int skipped, int downgradedToLow) {
        public String describe() {
            return "confirmed=%d rejected=%d noSignal=%d skipped=%d downgradedToLow=%d"
                    .formatted(confirmed, rejected, noSignal, skipped, downgradedToLow);
        }
    }

    private record TitleVerdict(Verdict verdict, boolean downgradedFromHigh) {}

    public RevalidationSummary revalidateOne(long titleId) {
        return revalidateBatch(List.of(titleId));
    }

    public RevalidationSummary revalidateBatch(List<Long> titleIds) {
        int confirmed = 0, rejected = 0, noSignal = 0, skipped = 0, downgradedToLow = 0;
        for (long titleId : titleIds) {
            try {
                TitleVerdict tv = jdbi.inTransaction(h -> revalidateOne(h, titleId));
                switch (tv.verdict()) {
                    case CONFIRMED -> confirmed++;
                    case REJECTED  -> {
                        rejected++;
                        if (tv.downgradedFromHigh()) downgradedToLow++;
                    }
                    case NO_SIGNAL -> noSignal++;
                }
            } catch (SkippedException e) {
                skipped++;
                log.debug("revalidation: skipped title {} — {}", titleId, e.getMessage());
            }
        }
        return new RevalidationSummary(confirmed, rejected, noSignal, skipped, downgradedToLow);
    }

    /**
     * Revalidates up to {@code limit} rows from the safety-net predicate:
     * {@code confidence='UNKNOWN'}, {@code last_revalidated_at IS NULL}, or
     * {@code last_revalidated_at < datetime('now', '-30 days')}.
     */
    public RevalidationSummary revalidateSafetyNetSlice(int limit) {
        List<Long> titleIds = jdbi.withHandle(h -> h.createQuery("""
                SELECT title_id FROM title_javdb_enrichment
                WHERE confidence = 'UNKNOWN'
                   OR last_revalidated_at IS NULL
                   OR last_revalidated_at < datetime('now', '-30 days')
                ORDER BY last_revalidated_at ASC NULLS FIRST
                LIMIT :limit
                """)
                .bind("limit", limit)
                .mapTo(Long.class)
                .list());
        return revalidateBatch(titleIds);
    }

    // ── per-title logic ────────────────────────────────────────────────────────

    private TitleVerdict revalidateOne(Handle h, long titleId) {
        // Load enrichment row + title code
        Map<String, Object> enrichRow = h.createQuery("""
                SELECT e.javdb_slug, e.confidence, t.code AS title_code
                FROM title_javdb_enrichment e
                JOIN titles t ON t.id = e.title_id
                WHERE e.title_id = :titleId
                """)
                .bind("titleId", titleId)
                .mapToMap()
                .findOne()
                .orElse(null);
        if (enrichRow == null) {
            throw new SkippedException("no enrichment row");
        }

        String javdbSlug      = (String) enrichRow.get("javdb_slug");
        String priorConfidence = (String) enrichRow.get("confidence");
        String titleCode      = (String) enrichRow.get("title_code");

        // Non-sentinel actress IDs linked to this title
        List<Long> actressIds = h.createQuery("""
                SELECT ta.actress_id
                FROM title_actresses ta
                JOIN actresses a ON a.id = ta.actress_id
                WHERE ta.title_id = :titleId AND a.is_sentinel = 0
                """)
                .bind("titleId", titleId)
                .mapTo(Long.class)
                .list();

        Verdict verdict = Verdict.NO_SIGNAL;

        outer:
        for (long actressId : actressIds) {
            // Actress's javdb slug from staging
            String actressSlug = h.createQuery(
                    "SELECT javdb_slug FROM javdb_actress_staging WHERE actress_id = :id")
                    .bind("id", actressId)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);
            if (actressSlug == null) continue;

            // Filmography entry for this actress + title code
            String entrySlug = h.createQuery("""
                    SELECT title_slug FROM javdb_actress_filmography_entry
                    WHERE actress_slug = :actressSlug AND product_code = :code
                    """)
                    .bind("actressSlug", actressSlug)
                    .bind("code", titleCode)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);

            if (entrySlug != null) {
                if (entrySlug.equals(javdbSlug)) {
                    verdict = Verdict.CONFIRMED;
                    break outer; // CONFIRMED wins immediately
                } else {
                    verdict = Verdict.REJECTED; // keep scanning — another actress might confirm
                }
            }
            // entrySlug == null → NO_SIGNAL from this actress, don't change verdict
        }

        // Always stamp last_revalidated_at; update confidence on CONFIRMED/REJECTED
        String newConfidence = switch (verdict) {
            case CONFIRMED -> "HIGH";
            case REJECTED  -> "LOW";
            case NO_SIGNAL -> priorConfidence; // unchanged
        };

        h.createUpdate("""
                UPDATE title_javdb_enrichment
                SET confidence          = :confidence,
                    last_revalidated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                WHERE title_id = :titleId
                """)
                .bind("confidence", newConfidence)
                .bind("titleId",    titleId)
                .execute();

        boolean downgradedFromHigh = verdict == Verdict.REJECTED && "HIGH".equals(priorConfidence);
        if (downgradedFromHigh) {
            h.createUpdate("""
                    INSERT OR IGNORE INTO enrichment_review_queue
                        (title_id, slug, reason, resolver_source)
                    VALUES (:titleId, :slug, 'no_match', 'revalidation')
                    """)
                    .bind("titleId", titleId)
                    .bind("slug",    javdbSlug)
                    .execute();
            log.info("revalidation: title {} ({}) downgraded HIGH→LOW, review queue opened", titleId, titleCode);
        }

        log.debug("revalidation: title {} ({}) → {} (prior: {})", titleId, titleCode, verdict, priorConfidence);
        return new TitleVerdict(verdict, downgradedFromHigh);
    }

    private static class SkippedException extends RuntimeException {
        SkippedException(String msg) { super(msg); }
    }
}
