package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.TitleEffectiveTagsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Writes the canonical javdb enrichment record for a title and the normalized
 * tag rows that go with it. Replaces the older write path through
 * {@link JavdbStagingRepository#upsertTitle}.
 *
 * <p>{@link #upsertEnrichment(long, String, String, TitleExtract)} performs an
 * atomic replace: the existing enrichment row (if any) is deleted — which
 * cascades to clear its tag assignments — and a fresh row is inserted along
 * with re-normalized tag definitions and assignments. {@code title_count} on
 * affected definitions is refreshed in the same transaction.
 */
@Slf4j
@RequiredArgsConstructor
public class JavdbEnrichmentRepository {

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final TitleEffectiveTagsService effectiveTags;
    private final EnrichmentHistoryRepository historyRepo;

    /**
     * Atomically writes (or replaces) the enrichment record + tag assignments for a title,
     * stamping the provenance columns supplied by the write-time gate.
     *
     * <p><b>Gate-bypass policy:</b> the only caller that may pass {@code resolverSource='manual_picker'}
     * is the future Wave 3A manual-picker UI. All automated paths (actress_filmography,
     * code_search_fallback, unknown) must be validated by {@code EnrichmentRunner.applyWriteGate}
     * before calling this method. Never call this method directly with automated provenance
     * values without running the gate — doing so would silently write unvalidated rows with
     * misleading confidence stamps.
     */
    public void upsertEnrichment(long titleId, String slug, String rawPath, TitleExtract extract,
                                 String resolverSource, String confidence, boolean castValidated) {
        upsertEnrichment(titleId, slug, rawPath, extract, resolverSource, confidence, castValidated,
                "enrichment_runner");
    }

    /**
     * Same as {@link #upsertEnrichment(long, String, String, TitleExtract, String, String, boolean)}
     * but records the given {@code historyReason} in the audit snapshot instead of
     * the default {@code "enrichment_runner"}. Use {@code "manual_override"} for
     * user-initiated force-enrich operations.
     */
    public void upsertEnrichment(long titleId, String slug, String rawPath, TitleExtract extract,
                                 String resolverSource, String confidence, boolean castValidated,
                                 String historyReason) {
        String thumbsJson = serialize(extract.thumbnailUrls());
        String castJson   = serialize(extract.cast());

        jdbi.useTransaction(h -> {
            // Snapshot prior state before any mutation so the audit trail is accurate.
            historyRepo.appendIfExists(titleId, historyReason, h);

            // Atomic replace: clear the old assignments first (FK ON DELETE CASCADE is
            // declared in the schema but not enforced — SQLite requires PRAGMA foreign_keys
            // = ON, which the application does not enable, so the cascade is explicit).
            h.createUpdate("DELETE FROM title_enrichment_tags WHERE title_id = :titleId")
                    .bind("titleId", titleId)
                    .execute();
            h.createUpdate("DELETE FROM title_javdb_enrichment WHERE title_id = :titleId")
                    .bind("titleId", titleId)
                    .execute();

            h.createUpdate("""
                    INSERT INTO title_javdb_enrichment (
                        title_id, javdb_slug, fetched_at, release_date, rating_avg, rating_count,
                        maker, publisher, series, title_original, duration_minutes,
                        cover_url, thumbnail_urls_json, cast_json, raw_path,
                        resolver_source, confidence, cast_validated
                    ) VALUES (
                        :titleId, :slug, :fetchedAt, :releaseDate, :ratingAvg, :ratingCount,
                        :maker, :publisher, :series, :titleOriginal, :durationMinutes,
                        :coverUrl, :thumbnailsJson, :castJson, :rawPath,
                        :resolverSource, :confidence, :castValidated
                    )""")
                    .bind("titleId",         titleId)
                    .bind("slug",            slug)
                    .bind("fetchedAt",       extract.fetchedAt())
                    .bind("releaseDate",     extract.releaseDate())
                    .bind("ratingAvg",       extract.ratingAvg())
                    .bind("ratingCount",     extract.ratingCount())
                    .bind("maker",           extract.maker())
                    .bind("publisher",       extract.publisher())
                    .bind("series",          extract.series())
                    .bind("titleOriginal",   extract.titleOriginal())
                    .bind("durationMinutes", extract.durationMinutes())
                    .bind("coverUrl",        extract.coverUrl())
                    .bind("thumbnailsJson",  thumbsJson)
                    .bind("castJson",        castJson)
                    .bind("rawPath",         rawPath)
                    .bind("resolverSource",  resolverSource)
                    .bind("confidence",      confidence)
                    .bind("castValidated",   castValidated ? 1 : 0)
                    .execute();

            // Tag normalization: insert any new tag definitions, then assignments.
            List<String> tags = extract.tags() == null ? List.of() : extract.tags();
            for (String raw : tags) {
                if (raw == null) continue;
                String name = raw.trim();
                if (name.isEmpty()) continue;
                h.createUpdate("INSERT OR IGNORE INTO enrichment_tag_definitions (name) VALUES (:name)")
                        .bind("name", name)
                        .execute();
                h.createUpdate("""
                        INSERT OR IGNORE INTO title_enrichment_tags (title_id, tag_id)
                        SELECT :titleId, id FROM enrichment_tag_definitions WHERE name = :name
                        """)
                        .bind("titleId", titleId)
                        .bind("name",    name)
                        .execute();
            }

            // Refresh title_count on every definition. This is the same code path the
            // initial backfill used; see SchemaUpgrader.applyV25. Bounded by the size of
            // the tag vocabulary (small), so a full recompute on each enrichment write is
            // simpler and equivalent to a targeted update.
            h.execute("""
                    UPDATE enrichment_tag_definitions
                    SET title_count = (
                        SELECT COUNT(*) FROM title_enrichment_tags
                        WHERE tag_id = enrichment_tag_definitions.id
                    )
                    """);
        });

        log.info("javdb: wrote enrichment row for title {} (slug={}, tags={})",
                titleId, slug, extract.tags() == null ? 0 : extract.tags().size());

        // Phase 4 integration: enrichment-derived effective tags must be re-derived now
        // so the curated tag UIs (actress detail Tags panel, title cards, browse filter)
        // immediately reflect the new alias-bridged tags. Synchronous to keep the read
        // model consistent with the just-completed write.
        effectiveTags.recomputeForTitle(titleId);
    }

    /**
     * Removes the enrichment record (and via CASCADE its tag assignments) and refreshes
     * title_count. Use when an attempt is invalidated or an existing record is being
     * cleared without immediate replacement.
     */
    public void deleteEnrichment(long titleId) {
        jdbi.useTransaction(h -> {
            // Snapshot prior state before clearing.
            historyRepo.appendIfExists(titleId, "cleanup", h);

            h.createUpdate("DELETE FROM title_enrichment_tags WHERE title_id = :titleId")
                    .bind("titleId", titleId)
                    .execute();
            int n = h.createUpdate("DELETE FROM title_javdb_enrichment WHERE title_id = :titleId")
                    .bind("titleId", titleId)
                    .execute();
            if (n > 0) {
                h.execute("""
                        UPDATE enrichment_tag_definitions
                        SET title_count = (
                            SELECT COUNT(*) FROM title_enrichment_tags
                            WHERE tag_id = enrichment_tag_definitions.id
                        )
                        """);
            }
        });
        // Recompute the title's effective tags so any enrichment-derived rows are cleared.
        effectiveTags.recomputeForTitle(titleId);
    }

    /** Count of enrichment rows with the given confidence value (HIGH/MEDIUM/LOW/UNKNOWN). */
    public int countByConfidence(String confidence) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE confidence = :confidence")
                        .bind("confidence", confidence)
                        .mapTo(Integer.class).one());
    }

    /** Count of enrichment rows with the given resolver_source value. */
    public int countByResolverSource(String source) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE resolver_source = :source")
                        .bind("source", source)
                        .mapTo(Integer.class).one());
    }

    /**
     * Returns {@code true} if at least one {@code title_javdb_enrichment} row references
     * the given javdb title slug. Used by drift detection to decide whether a vanished
     * filmography entry should be deleted (no references) or pinned as stale (has references).
     */
    public boolean findEnrichmentReferences(String titleSlug) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE javdb_slug = :slug")
                        .bind("slug", titleSlug)
                        .mapTo(Integer.class)
                        .one() > 0);
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize enrichment field", e);
        }
    }
}
