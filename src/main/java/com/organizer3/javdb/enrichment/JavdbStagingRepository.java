package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * JDBI repository for {@code javdb_title_staging} and {@code javdb_actress_staging},
 * plus raw JSON file I/O under {@code <dataDir>/javdb_raw/}.
 */
@Slf4j
@RequiredArgsConstructor
public class JavdbStagingRepository {

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final Path dataDir;

    // -------------------------------------------------------------------------
    // Title staging
    // -------------------------------------------------------------------------

    public void upsertTitle(JavdbTitleStagingRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_title_staging (
                    title_id, status, javdb_slug, raw_path, raw_fetched_at,
                    title_original, release_date, duration_minutes,
                    maker, publisher, series,
                    rating_avg, rating_count, tags_json, cast_json,
                    cover_url, thumbnail_urls_json
                ) VALUES (
                    :titleId, :status, :javdbSlug, :rawPath, :rawFetchedAt,
                    :titleOriginal, :releaseDate, :durationMinutes,
                    :maker, :publisher, :series,
                    :ratingAvg, :ratingCount, :tagsJson, :castJson,
                    :coverUrl, :thumbnailUrlsJson
                )
                ON CONFLICT(title_id) DO UPDATE SET
                    status              = excluded.status,
                    javdb_slug          = excluded.javdb_slug,
                    raw_path            = excluded.raw_path,
                    raw_fetched_at      = excluded.raw_fetched_at,
                    title_original      = excluded.title_original,
                    release_date        = excluded.release_date,
                    duration_minutes    = excluded.duration_minutes,
                    maker               = excluded.maker,
                    publisher           = excluded.publisher,
                    series              = excluded.series,
                    rating_avg          = excluded.rating_avg,
                    rating_count        = excluded.rating_count,
                    tags_json           = excluded.tags_json,
                    cast_json           = excluded.cast_json,
                    cover_url           = excluded.cover_url,
                    thumbnail_urls_json = excluded.thumbnail_urls_json
                """)
                .bind("titleId",          row.titleId())
                .bind("status",           row.status())
                .bind("javdbSlug",        row.javdbSlug())
                .bind("rawPath",          row.rawPath())
                .bind("rawFetchedAt",     row.rawFetchedAt())
                .bind("titleOriginal",    row.titleOriginal())
                .bind("releaseDate",      row.releaseDate())
                .bind("durationMinutes",  row.durationMinutes())
                .bind("maker",            row.maker())
                .bind("publisher",        row.publisher())
                .bind("series",           row.series())
                .bind("ratingAvg",        row.ratingAvg())
                .bind("ratingCount",      row.ratingCount())
                .bind("tagsJson",         row.tagsJson())
                .bind("castJson",         row.castJson())
                .bind("coverUrl",         row.coverUrl())
                .bind("thumbnailUrlsJson", row.thumbnailUrlsJson())
                .execute());
    }

    /** Marks a title as not found on javdb (permanent failure). */
    public void upsertTitleNotFound(long titleId) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_title_staging (title_id, status)
                VALUES (:titleId, 'not_found')
                ON CONFLICT(title_id) DO UPDATE SET status = 'not_found'
                """)
                .bind("titleId", titleId)
                .execute());
    }

    public Optional<JavdbTitleStagingRow> findTitleStaging(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT * FROM javdb_title_staging WHERE title_id = :titleId")
                .bind("titleId", titleId)
                .map((rs, ctx) -> new JavdbTitleStagingRow(
                        rs.getLong("title_id"),
                        rs.getString("status"),
                        rs.getString("javdb_slug"),
                        rs.getString("raw_path"),
                        rs.getString("raw_fetched_at"),
                        rs.getString("title_original"),
                        rs.getString("release_date"),
                        rs.getObject("duration_minutes") != null ? rs.getInt("duration_minutes") : null,
                        rs.getString("maker"),
                        rs.getString("publisher"),
                        rs.getString("series"),
                        rs.getObject("rating_avg") != null ? rs.getDouble("rating_avg") : null,
                        rs.getObject("rating_count") != null ? rs.getInt("rating_count") : null,
                        rs.getString("tags_json"),
                        rs.getString("cast_json"),
                        rs.getString("cover_url"),
                        rs.getString("thumbnail_urls_json")
                ))
                .findOne());
    }

    // -------------------------------------------------------------------------
    // Actress staging
    // -------------------------------------------------------------------------

    /**
     * Creates or updates the actress staging row with just a slug (slug_only status).
     * If a row already exists with status='fetched', the slug and source_title_code
     * are updated but status remains 'fetched'.
     */
    public void upsertActressSlugOnly(long actressId, String javdbSlug, String sourceTitleCode) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (actress_id, javdb_slug, source_title_code, status)
                VALUES (:actressId, :slug, :sourceTitleCode, 'slug_only')
                ON CONFLICT(actress_id) DO UPDATE SET
                    javdb_slug        = excluded.javdb_slug,
                    source_title_code = excluded.source_title_code
                """)
                .bind("actressId",      actressId)
                .bind("slug",           javdbSlug)
                .bind("sourceTitleCode", sourceTitleCode)
                .execute());
    }

    /** Upserts a fully fetched actress staging row. */
    public void upsertActress(JavdbActressStagingRow row) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO javdb_actress_staging (
                    actress_id, javdb_slug, source_title_code, status,
                    raw_path, raw_fetched_at,
                    name_variants_json, avatar_url,
                    twitter_handle, instagram_handle, title_count
                ) VALUES (
                    :actressId, :javdbSlug, :sourceTitleCode, :status,
                    :rawPath, :rawFetchedAt,
                    :nameVariantsJson, :avatarUrl,
                    :twitterHandle, :instagramHandle, :titleCount
                )
                ON CONFLICT(actress_id) DO UPDATE SET
                    javdb_slug         = excluded.javdb_slug,
                    status             = excluded.status,
                    raw_path           = excluded.raw_path,
                    raw_fetched_at     = excluded.raw_fetched_at,
                    name_variants_json = excluded.name_variants_json,
                    avatar_url         = excluded.avatar_url,
                    twitter_handle     = excluded.twitter_handle,
                    instagram_handle   = excluded.instagram_handle,
                    title_count        = excluded.title_count
                """)
                .bind("actressId",       row.actressId())
                .bind("javdbSlug",       row.javdbSlug())
                .bind("sourceTitleCode", row.sourceTitleCode())
                .bind("status",          row.status())
                .bind("rawPath",         row.rawPath())
                .bind("rawFetchedAt",    row.rawFetchedAt())
                .bind("nameVariantsJson", row.nameVariantsJson())
                .bind("avatarUrl",       row.avatarUrl())
                .bind("twitterHandle",   row.twitterHandle())
                .bind("instagramHandle", row.instagramHandle())
                .bind("titleCount",      row.titleCount())
                .execute());
    }

    /** Actress-slug pair derived from existing enrichment cast data, used for the startup backfill. */
    public record BackfillEntry(long actressId, String javdbSlug, String sourceTitleCode) {}

    /**
     * Returns actresses that have no staging row yet but whose enriched titles all share a single
     * consistent cast slug. This happens when the actress's name in our DB is romanized but javdb's
     * cast entries use Japanese — name matching fails, but the cast structure still identifies her.
     * Only actresses where every enriched single-cast title agrees on the same slug are returned
     * (HAVING COUNT(DISTINCT slug) = 1), so there is no ambiguity.
     */
    public List<BackfillEntry> findBackfillableActressSlugs() {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT ta.actress_id,
                       json_extract(tje.cast_json, '$[0].slug') AS javdb_slug,
                       MIN(t.code)                              AS title_code
                FROM title_actresses ta
                JOIN title_javdb_enrichment tje ON tje.title_id = ta.title_id
                JOIN titles t ON t.id = ta.title_id
                LEFT JOIN javdb_actress_staging jas ON jas.actress_id = ta.actress_id
                WHERE jas.actress_id IS NULL
                  AND json_array_length(tje.cast_json) = 1
                  AND json_extract(tje.cast_json, '$[0].slug') IS NOT NULL
                  AND json_extract(tje.cast_json, '$[0].slug') NOT IN (
                      SELECT javdb_slug FROM javdb_actress_staging WHERE javdb_slug IS NOT NULL
                  )
                GROUP BY ta.actress_id
                HAVING COUNT(DISTINCT json_extract(tje.cast_json, '$[0].slug')) = 1
                """)
                .map((rs, ctx) -> new BackfillEntry(
                        rs.getLong("actress_id"),
                        rs.getString("javdb_slug"),
                        rs.getString("title_code")))
                .list());
    }

    public Optional<JavdbActressStagingRow> findActressStaging(long actressId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT * FROM javdb_actress_staging WHERE actress_id = :actressId")
                .bind("actressId", actressId)
                .map((rs, ctx) -> new JavdbActressStagingRow(
                        rs.getLong("actress_id"),
                        rs.getString("javdb_slug"),
                        rs.getString("source_title_code"),
                        rs.getString("status"),
                        rs.getString("raw_path"),
                        rs.getString("raw_fetched_at"),
                        rs.getString("name_variants_json"),
                        rs.getString("avatar_url"),
                        rs.getString("twitter_handle"),
                        rs.getString("instagram_handle"),
                        rs.getObject("title_count") != null ? rs.getInt("title_count") : null
                ))
                .findOne());
    }

    // -------------------------------------------------------------------------
    // Raw JSON file I/O
    // -------------------------------------------------------------------------

    /**
     * Serializes the extract to JSON and writes it to
     * {@code javdb_raw/title/{slug}.json} under dataDir.
     *
     * @return the path relative to dataDir (for storage in staging.raw_path)
     */
    public String saveTitleRaw(String slug, TitleExtract extract) {
        String relPath = "javdb_raw/title/" + slug + ".json";
        Path abs = dataDir.resolve(relPath);
        try {
            Files.createDirectories(abs.getParent());
            json.writeValue(abs.toFile(), extract);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write title JSON: " + abs, e);
        }
        return relPath;
    }

    /**
     * Serializes the extract to JSON and writes it to
     * {@code javdb_raw/actress/{slug}.json} under dataDir.
     *
     * @return the path relative to dataDir
     */
    public String saveActressRaw(String slug, ActressExtract extract) {
        String relPath = "javdb_raw/actress/" + slug + ".json";
        Path abs = dataDir.resolve(relPath);
        try {
            Files.createDirectories(abs.getParent());
            json.writeValue(abs.toFile(), extract);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write actress JSON: " + abs, e);
        }
        return relPath;
    }
}
