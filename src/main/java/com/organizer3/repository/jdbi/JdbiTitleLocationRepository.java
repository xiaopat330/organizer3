package com.organizer3.repository.jdbi;

import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class JdbiTitleLocationRepository implements TitleLocationRepository {

    private static final RowMapper<TitleLocation> MAPPER = (rs, ctx) -> {
        String addedDateStr  = rs.getString("added_date");
        String staleSinceStr = rs.getString("stale_since");
        return TitleLocation.builder()
                .id(rs.getLong("id"))
                .titleId(rs.getLong("title_id"))
                .volumeId(rs.getString("volume_id"))
                .partitionId(rs.getString("partition_id"))
                .path(Path.of(rs.getString("path")))
                .lastSeenAt(LocalDate.parse(rs.getString("last_seen_at")))
                .addedDate(addedDateStr != null ? LocalDate.parse(addedDateStr) : null)
                .staleSince(staleSinceStr != null ? Instant.parse(staleSinceStr) : null)
                .build();
    };

    /**
     * The live-only predicate centralised here.
     * Appended to the WHERE clause of all default read queries.
     */
    private static final String LIVE_PREDICATE = "stale_since IS NULL";

    private final Jdbi jdbi;

    // -------------------------------------------------------------------------
    // Write path
    // -------------------------------------------------------------------------

    /**
     * Upserts a location row.
     *
     * <p>On conflict (same title_id, volume_id, path) the row is updated:
     * {@code stale_since} is cleared to NULL (re-observing a path makes it live),
     * {@code last_seen_at} is refreshed, and {@code partition_id} is updated in case
     * the folder moved between partitions on the same volume.
     */
    @Override
    public TitleLocation save(TitleLocation location) {
        return jdbi.withHandle(h -> {
            long id = h.createUpdate("""
                            INSERT INTO title_locations
                                (title_id, volume_id, partition_id, path, last_seen_at, added_date, stale_since)
                            VALUES (:titleId, :volumeId, :partitionId, :path, :lastSeenAt, :addedDate, NULL)
                            ON CONFLICT(title_id, volume_id, path)
                            DO UPDATE SET
                                stale_since   = NULL,
                                last_seen_at  = excluded.last_seen_at,
                                partition_id  = excluded.partition_id
                            """)
                    .bind("titleId", location.getTitleId())
                    .bind("volumeId", location.getVolumeId())
                    .bind("partitionId", location.getPartitionId())
                    .bind("path", location.getPath().toString())
                    .bind("lastSeenAt", location.getLastSeenAt().toString())
                    .bind("addedDate", location.getAddedDate() != null ? location.getAddedDate().toString() : null)
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();
            return location.toBuilder().id(id).staleSince(null).build();
        });
    }

    // -------------------------------------------------------------------------
    // Reads — default excludes stale rows; includeStale=true for reconcile / health
    // -------------------------------------------------------------------------

    @Override
    public List<TitleLocation> findByTitle(long titleId) {
        return findByTitle(titleId, false);
    }

    @Override
    public List<TitleLocation> findByTitle(long titleId, boolean includeStale) {
        String predicate = includeStale ? "" : " AND " + LIVE_PREDICATE;
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM title_locations WHERE title_id = :titleId" + predicate + " ORDER BY volume_id, path")
                        .bind("titleId", titleId)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<TitleLocation> findByTitleIds(List<Long> titleIds) {
        return findByTitleIds(titleIds, false);
    }

    @Override
    public List<TitleLocation> findByTitleIds(List<Long> titleIds, boolean includeStale) {
        if (titleIds.isEmpty()) return List.of();
        String predicate = includeStale ? "" : " AND " + LIVE_PREDICATE;
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM title_locations WHERE title_id IN (<titleIds>)" + predicate + " ORDER BY title_id, volume_id, path")
                        .bindList("titleIds", titleIds)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<TitleLocation> findByVolume(String volumeId) {
        return findByVolume(volumeId, false);
    }

    @Override
    public List<TitleLocation> findByVolume(String volumeId, boolean includeStale) {
        String predicate = includeStale ? "" : " AND " + LIVE_PREDICATE;
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM title_locations WHERE volume_id = :volumeId" + predicate + " ORDER BY path")
                        .bind("volumeId", volumeId)
                        .map(MAPPER)
                        .list()
        );
    }

    // -------------------------------------------------------------------------
    // Sync-path: mark-stale (grace-period orphan mechanics)
    // -------------------------------------------------------------------------

    @Override
    public int markStaleByVolume(String volumeId, String nowIso) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE title_locations
                        SET stale_since = :now
                        WHERE volume_id = :volumeId
                          AND stale_since IS NULL
                        """)
                        .bind("volumeId", volumeId)
                        .bind("now", nowIso)
                        .execute()
        );
    }

    @Override
    public int markStaleByVolumeAndPartition(String volumeId, String partitionId, String nowIso) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        UPDATE title_locations
                        SET stale_since = :now
                        WHERE volume_id = :volumeId
                          AND partition_id = :partitionId
                          AND stale_since IS NULL
                        """)
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .bind("now", nowIso)
                        .execute()
        );
    }

    // -------------------------------------------------------------------------
    // Sweep
    // -------------------------------------------------------------------------

    @Override
    public List<TitleLocation> findStaleOlderThan(int graceDays) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT * FROM title_locations
                        WHERE stale_since IS NOT NULL
                          AND stale_since < datetime('now', '-' || :days || ' days')
                        ORDER BY stale_since
                        """)
                        .bind("days", graceDays)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public int sweepStaleOlderThan(int graceDays) {
        return jdbi.withHandle(h ->
                h.createUpdate("""
                        DELETE FROM title_locations
                        WHERE stale_since IS NOT NULL
                          AND stale_since < datetime('now', '-' || :days || ' days')
                        """)
                        .bind("days", graceDays)
                        .execute()
        );
    }

    @Override
    public int countStaleMarkedAt(String volumeId, String markedAtIso) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(*) FROM title_locations
                        WHERE volume_id = :volumeId
                          AND stale_since = :markedAt
                        """)
                        .bind("volumeId", volumeId)
                        .bind("markedAt", markedAtIso)
                        .mapTo(Integer.class).one());
    }

    @Override
    public int countAllLive() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_locations WHERE stale_since IS NULL")
                        .mapTo(Integer.class).one());
    }

    @Override
    public PendingGraceSummary countPendingGrace() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT
                            COUNT(*) AS cnt,
                            COALESCE(MAX(CAST((julianday('now') - julianday(stale_since)) AS INTEGER)), 0) AS oldest_days
                        FROM title_locations
                        WHERE stale_since IS NOT NULL
                        """)
                .map((rs, ctx) -> {
                    int cnt = rs.getInt("cnt");
                    if (cnt == 0) return PendingGraceSummary.EMPTY;
                    return new PendingGraceSummary(cnt, rs.getInt("oldest_days"));
                })
                .one());
    }

    // -------------------------------------------------------------------------
    // Hard-delete variants (kept for tests / explicit admin cleanup)
    // -------------------------------------------------------------------------

    @Override
    @Deprecated
    public void deleteByVolume(String volumeId) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM title_locations WHERE volume_id = :volumeId")
                        .bind("volumeId", volumeId)
                        .execute()
        );
    }

    @Override
    public java.util.Optional<TitleLocation> findById(long locationId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM title_locations WHERE id = :id")
                        .bind("id", locationId)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    @Override
    public void deleteById(long locationId) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM title_locations WHERE id = :id")
                        .bind("id", locationId)
                        .execute()
        );
    }

    @Override
    @Deprecated
    public void deleteByVolumeAndPartition(String volumeId, String partitionId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM title_locations
                        WHERE volume_id = :volumeId AND partition_id = :partitionId""")
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .execute()
        );
    }

    @Override
    public void updatePathAndPartition(long locationId, Path newPath, String newPartitionId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE title_locations
                        SET path = :path, partition_id = :partitionId
                        WHERE id = :id""")
                        .bind("id", locationId)
                        .bind("path", newPath.toString())
                        .bind("partitionId", newPartitionId)
                        .execute()
        );
    }

    @Override
    public void updatePathPartitionAndVideos(long locationId, long titleId, String volumeId,
                                             String oldFolderPath, String newFolderPath,
                                             String newPartitionId) {
        jdbi.useTransaction(h -> {
            h.createUpdate("""
                    UPDATE title_locations
                    SET path = :newPath, partition_id = :partitionId
                    WHERE id = :id""")
                    .bind("id", locationId)
                    .bind("newPath", newFolderPath)
                    .bind("partitionId", newPartitionId)
                    .execute();
            h.createUpdate("""
                    UPDATE videos
                    SET path = :newPrefix || substr(path, length(:oldPrefix) + 1)
                    WHERE title_id = :titleId
                      AND volume_id = :volumeId
                      AND substr(path, 1, length(:oldPrefix)) = :oldPrefix
                    """)
                    .bind("newPrefix", newFolderPath)
                    .bind("oldPrefix", oldFolderPath)
                    .bind("titleId", titleId)
                    .bind("volumeId", volumeId)
                    .execute();
        });
    }

    @Override
    public void updatePath(long locationId, Path newPath, Handle h) {
        h.createUpdate("UPDATE title_locations SET path = :path WHERE id = :id")
                .bind("id", locationId)
                .bind("path", newPath.toString())
                .execute();
    }

    // -------------------------------------------------------------------------
    // Reconcile finder methods — read-only
    // -------------------------------------------------------------------------

    @Override
    public List<DuplicateLiveLocation> findDuplicateLiveLocations() {
        return jdbi.withHandle(h -> {
            // Find title_ids that have more than one live row
            List<Long> dupTitleIds = h.createQuery("""
                            SELECT title_id
                            FROM title_locations
                            WHERE stale_since IS NULL
                            GROUP BY title_id
                            HAVING COUNT(*) > 1
                            ORDER BY title_id
                            """)
                    .mapTo(Long.class)
                    .list();

            if (dupTitleIds.isEmpty()) return List.of();

            // Load all live rows for those titles, plus the title code
            List<Map<String, Object>> rawRows = h.createQuery("""
                            SELECT tl.id, tl.title_id, t.code, tl.volume_id, tl.partition_id, tl.path
                            FROM title_locations tl
                            JOIN titles t ON t.id = tl.title_id
                            WHERE tl.stale_since IS NULL
                              AND tl.title_id IN (<titleIds>)
                            ORDER BY tl.title_id, tl.volume_id, tl.path
                            """)
                    .bindList("titleIds", dupTitleIds)
                    .map((rs, ctx) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",          rs.getLong("id"));
                        m.put("title_id",    rs.getLong("title_id"));
                        m.put("code",        rs.getString("code"));
                        m.put("volume_id",   rs.getString("volume_id"));
                        m.put("partition_id",rs.getString("partition_id"));
                        m.put("path",        rs.getString("path"));
                        return m;
                    })
                    .list();

            // Group by title_id → DuplicateLiveLocation
            Map<Long, DuplicateLiveLocation> byTitle = new LinkedHashMap<>();
            for (Map<String, Object> row : rawRows) {
                long titleId  = (Long) row.get("title_id");
                String code   = (String) row.get("code");
                var loc = new DuplicateLiveLocation.LocationTuple(
                        (Long) row.get("id"),
                        (String) row.get("volume_id"),
                        (String) row.get("partition_id"),
                        (String) row.get("path"));
                byTitle.compute(titleId, (id, existing) -> {
                    if (existing == null) {
                        List<DuplicateLiveLocation.LocationTuple> locs = new ArrayList<>();
                        locs.add(loc);
                        return new DuplicateLiveLocation(titleId, code, locs);
                    } else {
                        existing.locations().add(loc);
                        return existing;
                    }
                });
            }
            return new ArrayList<>(byTitle.values());
        });
    }

    @Override
    public List<PendingGraceRow> findPendingGrace(int graceDays) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.id, tl.title_id, t.code, tl.volume_id, tl.path,
                               tl.stale_since,
                               CAST((julianday('now') - julianday(tl.stale_since)) AS INTEGER) AS days_stale
                        FROM title_locations tl
                        JOIN titles t ON t.id = tl.title_id
                        WHERE tl.stale_since IS NOT NULL
                          AND tl.stale_since >= datetime('now', '-' || :days || ' days')
                        ORDER BY tl.stale_since
                        """)
                        .bind("days", graceDays)
                        .map((rs, ctx) -> new PendingGraceRow(
                                rs.getLong("id"),
                                rs.getLong("title_id"),
                                rs.getString("code"),
                                rs.getString("volume_id"),
                                rs.getString("path"),
                                rs.getString("stale_since"),
                                rs.getInt("days_stale")))
                        .list()
        );
    }

    @Override
    public List<PendingGraceRow> findPastGraceStragglers(int graceDays) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tl.id, tl.title_id, t.code, tl.volume_id, tl.path,
                               tl.stale_since,
                               CAST((julianday('now') - julianday(tl.stale_since)) AS INTEGER) AS days_stale
                        FROM title_locations tl
                        JOIN titles t ON t.id = tl.title_id
                        WHERE tl.stale_since IS NOT NULL
                          AND tl.stale_since < datetime('now', '-' || :days || ' days')
                        ORDER BY tl.stale_since
                        """)
                        .bind("days", graceDays)
                        .map((rs, ctx) -> new PendingGraceRow(
                                rs.getLong("id"),
                                rs.getLong("title_id"),
                                rs.getString("code"),
                                rs.getString("volume_id"),
                                rs.getString("path"),
                                rs.getString("stale_since"),
                                rs.getInt("days_stale")))
                        .list()
        );
    }
}
