package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Applies auto-promote rules from javdb enrichment data to canonical tables.
 *
 * <p>Rules (spec §Hybrid import):
 * <ul>
 *   <li>{@code titles.title_original} — when NULL, fill from {@code title_javdb_enrichment}.</li>
 *   <li>{@code titles.release_date}   — when NULL, fill from {@code title_javdb_enrichment}.</li>
 *   <li>{@code actresses.stage_name}  — when NULL, fill from the first cast_json entry
 *       whose javdb slug matches the actress's {@code javdb_actress_staging} row.</li>
 * </ul>
 * All three rules are idempotent and never overwrite an existing non-null value.
 *
 * <p>Additionally, after {@code fetch_actress_profile} completes,
 * {@link #promoteFromActressProfile(long)} applies two additional rules using the
 * richer {@code name_variants_json} from the actress's javdb profile page:
 * <ul>
 *   <li>Rule 2: if {@code stage_name} has no CJK characters, update from {@code name_variants_json[0]}.</li>
 *   <li>Rule 3: if {@code stage_name} already has CJK but does not appear in {@code name_variants_json},
 *       log a WARN and enqueue a {@code stage_name_conflict} review row.</li>
 * </ul>
 */
@Slf4j
public class AutoPromoter {

    /**
     * Regex detecting presence of CJK characters (kanji, hiragana, katakana).
     * Excludes CJK Extension A and half-width katakana — deferred as noted in spec.
     */
    static final String CJK_PATTERN = ".*[\\u4E00-\\u9FFF\\u3041-\\u3096\\u30A1-\\u30FA].*";

    private static final ObjectMapper DETAIL_JSON = new ObjectMapper();

    private final Jdbi jdbi;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;

    /**
     * Full constructor (production path) — includes reviewQueueRepo for Rule 3 conflict logging.
     */
    public AutoPromoter(Jdbi jdbi, EnrichmentReviewQueueRepository reviewQueueRepo) {
        this.jdbi = jdbi;
        this.reviewQueueRepo = reviewQueueRepo;
    }

    /**
     * Backwards-compat constructor for tests that only exercise Rule 1 (cast_json path).
     * Rule 3 conflict logging is silently disabled when reviewQueueRepo is null.
     */
    public AutoPromoter(Jdbi jdbi) {
        this(jdbi, null);
    }

    /**
     * Called after a {@code fetch_title} job completes.
     * Promotes title_original and release_date for the title, then tries stage_name for the actress.
     */
    public void promoteFromTitle(long titleId, long actressId) {
        int titleOriginalUpdated = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE titles
                SET title_original = (SELECT title_original FROM title_javdb_enrichment WHERE title_id = :titleId)
                WHERE id = :titleId AND title_original IS NULL
                  AND (SELECT title_original FROM title_javdb_enrichment WHERE title_id = :titleId) IS NOT NULL
                """)
                .bind("titleId", titleId)
                .execute());

        int releaseDateUpdated = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE titles
                SET release_date = (SELECT release_date FROM title_javdb_enrichment WHERE title_id = :titleId)
                WHERE id = :titleId AND release_date IS NULL
                  AND (SELECT release_date FROM title_javdb_enrichment WHERE title_id = :titleId) IS NOT NULL
                """)
                .bind("titleId", titleId)
                .execute());

        if (titleOriginalUpdated > 0) log.info("javdb: promoted title_original for title {}", titleId);
        if (releaseDateUpdated > 0)   log.info("javdb: promoted release_date for title {}", titleId);

        promoteActressStageName(actressId);
    }

    /**
     * Called after {@code fetch_actress_profile} completes.
     * Applies stage_name promotion rules using {@code javdb_actress_staging.name_variants_json}
     * as the authoritative source.
     *
     * <p>Rule 1 extension: if {@code stage_name IS NULL} and {@code name_variants_json[0]} is non-null,
     * fill it. (Complementary to the cast_json path in {@link #promoteActressStageName}.)
     *
     * <p>Rule 2: if existing {@code stage_name} contains no CJK characters, update from
     * {@code name_variants_json[0]}.
     *
     * <p>Rule 3: if existing {@code stage_name} already contains CJK but does NOT appear in
     * {@code name_variants_json}, log a WARN and enqueue a {@code stage_name_conflict} review row.
     * The stage_name is NOT overwritten.
     *
     * <p>No-op when {@code name_variants_json} is empty or null.
     */
    public void promoteFromActressProfile(long actressId) {
        // Read the staging data
        StagingData staging = jdbi.withHandle(h -> h.createQuery("""
                SELECT jas.name_variants_json,
                       a.stage_name,
                       (SELECT t.id FROM title_actresses ta
                        JOIN titles t ON t.id = ta.title_id
                        WHERE ta.actress_id = :actressId LIMIT 1) AS any_title_id
                FROM javdb_actress_staging jas
                JOIN actresses a ON a.id = :actressId
                WHERE jas.actress_id = :actressId
                """)
                .bind("actressId", actressId)
                .map((rs, ctx) -> {
                    Object rawTitleId = rs.getObject("any_title_id");
                    Long titleId = rawTitleId == null ? null : ((Number) rawTitleId).longValue();
                    return new StagingData(
                            rs.getString("name_variants_json"),
                            rs.getString("stage_name"),
                            titleId);
                })
                .findOne()
                .orElse(null));

        if (staging == null) return;

        // Parse name_variants_json — expected shape: ["name1", "name2", ...]
        List<String> variants = parseNameVariants(staging.nameVariantsJson());
        if (variants == null || variants.isEmpty()) return;

        String variant0 = variants.get(0);
        String currentStageName = staging.stageName();

        // Rule 1 & 2: fill NULL stage_name, or correct a non-CJK stage_name
        if (currentStageName == null || !currentStageName.matches(CJK_PATTERN)) {
            int updated = jdbi.withHandle(h -> h.createUpdate("""
                    UPDATE actresses
                    SET stage_name = :variant
                    WHERE id = :actressId
                      AND :variant IS NOT NULL
                    """)
                    .bind("actressId", actressId)
                    .bind("variant", variant0)
                    .execute());
            if (updated > 0) {
                log.info("javdb: promoted stage_name for actress {} → '{}' (from name_variants_json)",
                        actressId, variant0);
            }
            return;
        }

        // Rule 3: stage_name has CJK — check if it appears in variants
        boolean matchesAVariant = variants.stream().anyMatch(v -> v != null && v.equals(currentStageName));
        if (!matchesAVariant) {
            log.warn("javdb: stage_name conflict for actress {}: ours=\"{}\", javdb variants={}",
                    actressId, currentStageName, variants);
            enqueueStageNameConflict(actressId, currentStageName, variants, staging.anyTitleId());
        }
        // If it matches a variant, this is a no-op (no log, no queue row)
    }

    private void enqueueStageNameConflict(long actressId, String ourStageName,
                                          List<String> javdbVariants, Long titleId) {
        if (reviewQueueRepo == null) return;
        if (titleId == null) {
            log.warn("javdb: stage_name_conflict for actress {} but no title found — skipping queue row", actressId);
            return;
        }
        try {
            ObjectNode detail = DETAIL_JSON.createObjectNode();
            detail.put("our_stage_name", ourStageName);
            ArrayNode variantsNode = detail.putArray("javdb_variants");
            for (String v : javdbVariants) {
                if (v != null) variantsNode.add(v);
            }
            reviewQueueRepo.enqueueWithDetail(titleId, null, "stage_name_conflict",
                    "actress_profile", detail.toString());
        } catch (Exception e) {
            log.warn("javdb: failed to enqueue stage_name_conflict for actress {}: {}", actressId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseNameVariants(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return (List<String>) DETAIL_JSON.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("javdb: failed to parse name_variants_json for actress: {}", e.toString());
            return null;
        }
    }

    private record StagingData(String nameVariantsJson, String stageName, Long anyTitleId) {}

    /**
     * Called after a {@code fetch_actress_profile} job completes, and after every title fetch.
     * Promotes stage_name for the actress from a cast_json entry whose slug matches her staging slug.
     */
    public void promoteActressStageName(long actressId) {
        int updated = jdbi.withHandle(h -> h.createUpdate("""
                UPDATE actresses
                SET stage_name = (
                    SELECT json_extract(je.value, '$.name')
                    FROM title_actresses ta
                    JOIN title_javdb_enrichment tje ON tje.title_id = ta.title_id
                    JOIN javdb_actress_staging jas ON jas.actress_id = :actressId
                    JOIN json_each(tje.cast_json) je
                        ON json_extract(je.value, '$.slug') = jas.javdb_slug
                    WHERE ta.actress_id = :actressId
                      AND tje.cast_json IS NOT NULL
                      AND jas.javdb_slug IS NOT NULL
                    LIMIT 1
                )
                WHERE id = :actressId AND stage_name IS NULL
                  AND EXISTS (
                    SELECT 1
                    FROM title_actresses ta
                    JOIN title_javdb_enrichment tje ON tje.title_id = ta.title_id
                    JOIN javdb_actress_staging jas ON jas.actress_id = :actressId
                    JOIN json_each(tje.cast_json) je
                        ON json_extract(je.value, '$.slug') = jas.javdb_slug
                    WHERE ta.actress_id = :actressId
                      AND tje.cast_json IS NOT NULL
                      AND jas.javdb_slug IS NOT NULL
                )
                """)
                .bind("actressId", actressId)
                .execute());

        if (updated > 0) log.info("javdb: promoted stage_name for actress {}", actressId);
    }
}
