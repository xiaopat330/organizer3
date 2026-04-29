package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class JdbiJavdbActressFilmographyRepository implements JavdbActressFilmographyRepository {

    private final Jdbi jdbi;

    @Override
    public Optional<FilmographyMeta> findMeta(String actressSlug) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT actress_slug, fetched_at, page_count, last_release_date, source
                        FROM javdb_actress_filmography
                        WHERE actress_slug = :slug
                        """)
                        .bind("slug", actressSlug)
                        .map((rs, ctx) -> new FilmographyMeta(
                                rs.getString("actress_slug"),
                                rs.getString("fetched_at"),
                                rs.getInt("page_count"),
                                rs.getString("last_release_date"),
                                rs.getString("source")))
                        .findOne());
    }

    @Override
    public Map<String, String> getCodeToSlug(String actressSlug) {
        return jdbi.withHandle(h -> {
            Map<String, String> result = new HashMap<>();
            h.createQuery("""
                    SELECT product_code, title_slug
                    FROM javdb_actress_filmography_entry
                    WHERE actress_slug = :slug
                    """)
                    .bind("slug", actressSlug)
                    .map((rs, ctx) -> Map.entry(rs.getString("product_code"), rs.getString("title_slug")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    @Override
    public Optional<String> findTitleSlug(String actressSlug, String productCode) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT title_slug
                        FROM javdb_actress_filmography_entry
                        WHERE actress_slug = :slug AND product_code = :code
                        """)
                        .bind("slug", actressSlug)
                        .bind("code", productCode)
                        .mapTo(String.class)
                        .findOne());
    }

    @Override
    public void upsertFilmography(String actressSlug, FetchResult result) {
        jdbi.useTransaction(h -> {
            // Explicit deletes because SQLite ON DELETE CASCADE requires foreign_keys=ON
            // which the application does not enable.
            h.createUpdate("DELETE FROM javdb_actress_filmography_entry WHERE actress_slug = :slug")
                    .bind("slug", actressSlug)
                    .execute();
            h.createUpdate("DELETE FROM javdb_actress_filmography WHERE actress_slug = :slug")
                    .bind("slug", actressSlug)
                    .execute();

            h.createUpdate("""
                    INSERT INTO javdb_actress_filmography (actress_slug, fetched_at, page_count, last_release_date, source)
                    VALUES (:slug, :fetchedAt, :pageCount, :lastReleaseDate, :source)
                    """)
                    .bind("slug",            actressSlug)
                    .bind("fetchedAt",       result.fetchedAt())
                    .bind("pageCount",       result.pageCount())
                    .bind("lastReleaseDate", result.lastReleaseDate())
                    .bind("source",          result.source())
                    .execute();

            var batch = h.prepareBatch("""
                    INSERT OR IGNORE INTO javdb_actress_filmography_entry (actress_slug, product_code, title_slug)
                    VALUES (:slug, :code, :titleSlug)
                    """);
            for (FilmographyEntry entry : result.entries()) {
                batch.bind("slug",      actressSlug)
                        .bind("code",      entry.productCode())
                        .bind("titleSlug", entry.titleSlug())
                        .add();
            }
            if (!result.entries().isEmpty()) {
                batch.execute();
            }
        });
        log.debug("javdb: persisted filmography for {} — {} entries", actressSlug, result.entries().size());
    }

    @Override
    public void evict(String actressSlug) {
        jdbi.useHandle(h -> {
            h.createUpdate("DELETE FROM javdb_actress_filmography_entry WHERE actress_slug = :slug")
                    .bind("slug", actressSlug)
                    .execute();
            h.createUpdate("DELETE FROM javdb_actress_filmography WHERE actress_slug = :slug")
                    .bind("slug", actressSlug)
                    .execute();
        });
        log.debug("javdb: evicted L2 filmography for {}", actressSlug);
    }

    @Override
    public boolean isStale(String actressSlug, int ttlDays, Clock clock) {
        Optional<FilmographyMeta> metaOpt = findMeta(actressSlug);
        if (metaOpt.isEmpty()) return true;

        FilmographyMeta meta = metaOpt.get();
        Instant fetchedAt = Instant.parse(meta.fetchedAt());
        Instant now = clock.instant();
        Instant expiry = fetchedAt.plus(ttlDays, ChronoUnit.DAYS);

        if (now.isBefore(expiry)) return false;

        // Past TTL — check settled-catalog exemption: if last release is >2 years old,
        // treat this actress as retired and skip re-fetching.
        String lastRelease = meta.lastReleaseDate();
        if (lastRelease != null) {
            LocalDate releaseDate = LocalDate.parse(lastRelease);
            LocalDate twoYearsAgo = LocalDate.now(clock).minusYears(2);
            if (releaseDate.isBefore(twoYearsAgo)) return false;
        }

        return true;
    }
}
