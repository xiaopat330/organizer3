package com.organizer3.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deletes thumbnail directories for titles that have gone cold.
 *
 * <p>A title is evicted only if ALL of these hold:
 * <ul>
 *   <li>{@code titles.last_visited_at} is NULL or older than {@code evictionDays}</li>
 *   <li>{@code titles.bookmark = 0}</li>
 *   <li>{@code titles.favorite = 0}</li>
 *   <li>{@code titles.favorite_cleared_at} is NULL or older than {@code evictionDays}</li>
 *   <li>no linked actress has {@code favorite = 1}</li>
 *   <li>no linked actress has {@code favorite_cleared_at} newer than {@code evictionDays} ago</li>
 * </ul>
 *
 * <p>Favorites are sticky perpetually. On un-favorite, a {@code evictionDays} grace
 * window keeps the thumbnails around — re-favoriting during the window clears the
 * stamp and restores perpetual retention. Bookmarks are sticky only while set (no
 * grace). Unknown title directories (code not in DB at all) are left alone here —
 * {@code prune-thumbnails} handles those.
 */
@Slf4j
@RequiredArgsConstructor
public class ThumbnailEvictor {

    private final Jdbi jdbi;
    private final ThumbnailService thumbnailService;

    /** Runs one sweep. Returns number of title directories removed. */
    public int sweep(int evictionDays) {
        if (evictionDays <= 0) return 0;
        Path root = thumbnailService.root();
        if (!Files.isDirectory(root)) return 0;

        Set<String> evictable = loadEvictableTitleCodes(evictionDays);
        if (evictable.isEmpty()) return 0;

        int removed = 0;
        try (Stream<Path> titleDirs = Files.list(root)) {
            for (Path titleDir : titleDirs.filter(Files::isDirectory).toList()) {
                String code = titleDir.getFileName().toString();
                if (!evictable.contains(code)) continue;
                try {
                    deleteRecursively(titleDir);
                    log.info("Evicted cold thumbnails: {}", code);
                    removed++;
                } catch (IOException e) {
                    log.warn("Failed to evict {}: {}", titleDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Eviction scan failed: {}", e.getMessage());
        }
        return removed;
    }

    private Set<String> loadEvictableTitleCodes(int evictionDays) {
        // A linked-actress signal is "recent" if the actress is currently favorited,
        // or her favorite_cleared_at is within the grace window.
        String sql = """
            WITH title_actress_signal AS (
                SELECT ta.title_id,
                       MAX(CASE WHEN a.favorite = 1 THEN 1 ELSE 0 END) AS any_fav,
                       MAX(CASE
                             WHEN a.favorite_cleared_at IS NOT NULL
                              AND (julianday('now') - julianday(a.favorite_cleared_at)) <= :days
                             THEN 1 ELSE 0
                           END) AS any_in_grace
                FROM title_actresses ta
                JOIN actresses a ON a.id = ta.actress_id
                GROUP BY ta.title_id
            )
            SELECT t.code
            FROM titles t
            LEFT JOIN title_actress_signal tas ON tas.title_id = t.id
            WHERE t.favorite = 0
              AND t.bookmark = 0
              AND COALESCE(tas.any_fav,      0) = 0
              AND COALESCE(tas.any_in_grace, 0) = 0
              AND (t.favorite_cleared_at IS NULL
                   OR (julianday('now') - julianday(t.favorite_cleared_at)) > :days)
              AND (t.last_visited_at IS NULL
                   OR (julianday('now') - julianday(t.last_visited_at)) > :days)
            """;
        List<String> codes = jdbi.withHandle(h -> h.createQuery(sql)
                .bind("days", evictionDays)
                .mapTo(String.class)
                .list());
        return Set.copyOf(codes);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
