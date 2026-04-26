package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * Atomically writes (or replaces) the enrichment record + tag assignments for a title.
     */
    public void upsertEnrichment(long titleId, String slug, String rawPath, TitleExtract extract) {
        String thumbsJson = serialize(extract.thumbnailUrls());
        String castJson   = serialize(extract.cast());

        jdbi.useTransaction(h -> {
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
                        cover_url, thumbnail_urls_json, cast_json, raw_path
                    ) VALUES (
                        :titleId, :slug, :fetchedAt, :releaseDate, :ratingAvg, :ratingCount,
                        :maker, :publisher, :series, :titleOriginal, :durationMinutes,
                        :coverUrl, :thumbnailsJson, :castJson, :rawPath
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
    }

    /**
     * Removes the enrichment record (and via CASCADE its tag assignments) and refreshes
     * title_count. Use when an attempt is invalidated or an existing record is being
     * cleared without immediate replacement.
     */
    public void deleteEnrichment(long titleId) {
        jdbi.useTransaction(h -> {
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
