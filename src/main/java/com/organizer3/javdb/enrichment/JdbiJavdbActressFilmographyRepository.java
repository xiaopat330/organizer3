package com.organizer3.javdb.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class JdbiJavdbActressFilmographyRepository implements JavdbActressFilmographyRepository {

    private final Jdbi jdbi;
    private final RevalidationPendingRepository revalidationPendingRepo;

    @Override
    public Optional<FilmographyMeta> findMeta(String actressSlug) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT actress_slug, fetched_at, page_count, last_release_date, source,
                               last_drift_count, last_fetch_status
                        FROM javdb_actress_filmography
                        WHERE actress_slug = :slug
                        """)
                        .bind("slug", actressSlug)
                        .map((rs, ctx) -> new FilmographyMeta(
                                rs.getString("actress_slug"),
                                rs.getString("fetched_at"),
                                rs.getInt("page_count"),
                                rs.getString("last_release_date"),
                                rs.getString("source"),
                                rs.getInt("last_drift_count"),
                                rs.getString("last_fetch_status")))
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

    /**
     * Atomically persists the new filmography for this actress with drift detection.
     *
     * <p>All operations run in a single transaction so the DB never holds a partial state.
     * The diff is computed in memory after reading existing rows; then inserts, updates,
     * and deletes (or stale markers) are applied. The metadata row is replaced last.
     *
     * <p>Drift rules:
     * <ul>
     *   <li>New (code, slug) pair → INSERT</li>
     *   <li>Existing pair, slug changed → UPDATE + bump drift count + INFO log</li>
     *   <li>Existing pair, slug unchanged → UPDATE stale=0 (un-stale if previously stale)</li>
     *   <li>Cached pair absent from new fetch, no enriched titles reference it → DELETE</li>
     *   <li>Cached pair absent from new fetch, enriched titles reference it → UPDATE stale=1 + bump drift count</li>
     * </ul>
     */
    @Override
    public void upsertFilmography(String actressSlug, FetchResult result) {
        jdbi.useTransaction(h -> {
            // 1. Read existing entries for diff
            Map<String, String> existing = new HashMap<>();
            h.createQuery("""
                    SELECT product_code, title_slug
                    FROM javdb_actress_filmography_entry
                    WHERE actress_slug = :slug
                    """)
                    .bind("slug", actressSlug)
                    .map((rs, ctx) -> Map.entry(rs.getString("product_code"), rs.getString("title_slug")))
                    .forEach(e -> existing.put(e.getKey(), e.getValue()));

            // 2. Build incoming map for O(1) lookup during vanished-entry scan
            Map<String, String> incoming = new HashMap<>();
            for (FilmographyEntry entry : result.entries()) {
                incoming.put(entry.productCode(), entry.titleSlug());
            }

            int driftCount = 0;

            // 3. Process entries from the new fetch: insert new, update changed/same
            for (FilmographyEntry entry : result.entries()) {
                String code = entry.productCode();
                String newSlug = entry.titleSlug();
                String oldSlug = existing.get(code);

                if (oldSlug == null) {
                    // New entry — never seen before
                    h.createUpdate("""
                            INSERT OR IGNORE INTO javdb_actress_filmography_entry
                                (actress_slug, product_code, title_slug, stale)
                            VALUES (:slug, :code, :titleSlug, 0)
                            """)
                            .bind("slug",     actressSlug)
                            .bind("code",     code)
                            .bind("titleSlug", newSlug)
                            .execute();
                } else {
                    // Existing entry: reset stale and update slug (detect drift)
                    if (!oldSlug.equals(newSlug)) {
                        driftCount++;
                        log.info("javdb: drift detected for {}: {} {} → {}", actressSlug, code, oldSlug, newSlug);
                        List<Long> affectedTitleIds = h.createQuery(
                                "SELECT title_id FROM title_javdb_enrichment WHERE javdb_slug = :slug")
                                .bind("slug", oldSlug)
                                .mapTo(Long.class)
                                .list();
                        for (long affectedId : affectedTitleIds) {
                            revalidationPendingRepo.enqueue(affectedId, "drift", h);
                        }
                    }
                    h.createUpdate("""
                            UPDATE javdb_actress_filmography_entry
                            SET title_slug = :titleSlug, stale = 0
                            WHERE actress_slug = :slug AND product_code = :code
                            """)
                            .bind("titleSlug", newSlug)
                            .bind("slug",      actressSlug)
                            .bind("code",      code)
                            .execute();
                }
            }

            // 4. Handle vanished entries (in existing but absent from the new fetch)
            for (Map.Entry<String, String> old : existing.entrySet()) {
                String code = old.getKey();
                String oldSlug = old.getValue();
                if (incoming.containsKey(code)) continue;

                // Find any enriched titles that reference this (now-vanished) slug.
                // Query runs on the same handle so it's within the same transaction.
                // SQLite FK enforcement is off in this application; see JavdbEnrichmentRepository.
                List<Long> refTitleIds = h.createQuery("""
                        SELECT title_id FROM title_javdb_enrichment WHERE javdb_slug = :slug
                        """)
                        .bind("slug", oldSlug)
                        .mapTo(Long.class)
                        .list();

                if (!refTitleIds.isEmpty()) {
                    driftCount++;
                    h.createUpdate("""
                            UPDATE javdb_actress_filmography_entry SET stale = 1
                            WHERE actress_slug = :slug AND product_code = :code
                            """)
                            .bind("slug", actressSlug)
                            .bind("code", code)
                            .execute();
                    for (long refId : refTitleIds) {
                        revalidationPendingRepo.enqueue(refId, "drift", h);
                    }
                } else {
                    h.createUpdate("""
                            DELETE FROM javdb_actress_filmography_entry
                            WHERE actress_slug = :slug AND product_code = :code
                            """)
                            .bind("slug", actressSlug)
                            .bind("code", code)
                            .execute();
                }
            }

            // 5. Upsert metadata row: DELETE + INSERT to reset all fields cleanly.
            // Entries are managed individually above, so deleting only the metadata row is safe
            // (no FK cascade fires since foreign_keys pragma is not enabled).
            h.createUpdate("DELETE FROM javdb_actress_filmography WHERE actress_slug = :slug")
                    .bind("slug", actressSlug)
                    .execute();
            h.createUpdate("""
                    INSERT INTO javdb_actress_filmography
                        (actress_slug, fetched_at, page_count, last_release_date, source,
                         last_drift_count, last_fetch_status)
                    VALUES (:slug, :fetchedAt, :pageCount, :lastReleaseDate, :source, :driftCount, 'ok')
                    """)
                    .bind("slug",            actressSlug)
                    .bind("fetchedAt",       result.fetchedAt())
                    .bind("pageCount",       result.pageCount())
                    .bind("lastReleaseDate", result.lastReleaseDate())
                    .bind("source",          result.source())
                    .bind("driftCount",      driftCount)
                    .execute();
        });
        log.debug("javdb: persisted filmography for {} — {} entries", actressSlug, result.entries().size());
    }

    @Override
    public java.util.List<String> findAllActressSlugs() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT actress_slug FROM javdb_actress_filmography ORDER BY actress_slug")
                        .mapTo(String.class)
                        .list());
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

    /**
     * Records a 404 outcome: upserts metadata with {@code last_fetch_status='not_found'} and
     * marks all entries {@code stale=1}. Runs in a single transaction.
     */
    @Override
    public int markNotFound(String actressSlug, String fetchedAt) {
        return jdbi.inTransaction(h -> {
            h.createUpdate("""
                    INSERT INTO javdb_actress_filmography
                        (actress_slug, fetched_at, page_count, last_release_date, source,
                         last_drift_count, last_fetch_status)
                    VALUES (:slug, :fetchedAt, 0, NULL, 'http', 0, 'not_found')
                    ON CONFLICT(actress_slug) DO UPDATE SET
                        fetched_at        = :fetchedAt,
                        last_fetch_status = 'not_found'
                    """)
                    .bind("slug",      actressSlug)
                    .bind("fetchedAt", fetchedAt)
                    .execute();

            return h.createUpdate("""
                    UPDATE javdb_actress_filmography_entry SET stale = 1
                    WHERE actress_slug = :slug
                    """)
                    .bind("slug", actressSlug)
                    .execute();
        });
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
