package com.organizer3.repository.jdbi;

import com.organizer3.repository.UnsortedEditorRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@RequiredArgsConstructor
public class JdbiUnsortedEditorRepository implements UnsortedEditorRepository {

    private final Jdbi jdbi;

    @Override
    public List<EligibleTitle> listEligible(String volumeId) {
        String sql = """
                SELECT t.id AS title_id, t.code AS code, t.label AS label, t.base_code AS base_code,
                       tl.path AS folder_path,
                       (SELECT COUNT(*) FROM title_actresses ta WHERE ta.title_id = t.id) AS actress_count
                FROM titles t
                JOIN title_locations tl ON tl.title_id = t.id
                WHERE tl.volume_id = :volumeId
                  AND t.code IS NOT NULL
                  AND t.base_code IS NOT NULL
                  AND tl.path LIKE '%(' || t.code || ')%'
                  AND EXISTS (
                      SELECT 1 FROM videos v
                      WHERE v.title_id = t.id
                        AND v.volume_id = :volumeId
                        AND (
                            substr(v.path, length(tl.path) + 2) LIKE 'video/%'
                         OR substr(v.path, length(tl.path) + 2) LIKE 'h265/%'
                         OR substr(v.path, length(tl.path) + 2) LIKE '4K/%'
                        )
                  )
                ORDER BY tl.added_date ASC, t.id ASC
                """;
        return jdbi.withHandle(h -> h.createQuery(sql)
                .bind("volumeId", volumeId)
                .map((rs, ctx) -> {
                    String path = rs.getString("folder_path");
                    String folderName = basename(path);
                    return new EligibleTitle(
                            rs.getLong("title_id"),
                            rs.getString("code"),
                            folderName,
                            rs.getString("label"),
                            rs.getString("base_code"),
                            rs.getInt("actress_count"),
                            path
                    );
                })
                .list());
    }

    @Override
    public Optional<TitleDetail> findEligibleById(long titleId, String volumeId) {
        return jdbi.withHandle(h -> {
            var detailOpt = h.createQuery("""
                    SELECT t.id AS title_id, t.code AS code, t.label AS label, t.base_code AS base_code,
                           t.actress_id AS primary_actress_id, tl.path AS folder_path
                    FROM titles t
                    JOIN title_locations tl ON tl.title_id = t.id
                    WHERE t.id = :titleId AND tl.volume_id = :volumeId
                    LIMIT 1
                    """)
                    .bind("titleId", titleId)
                    .bind("volumeId", volumeId)
                    .map((rs, ctx) -> new Object[]{
                            rs.getLong("title_id"),
                            rs.getString("code"),
                            rs.getString("label"),
                            rs.getString("base_code"),
                            (Long) (rs.getObject("primary_actress_id") == null ? null : rs.getLong("primary_actress_id")),
                            rs.getString("folder_path")
                    })
                    .findFirst();
            if (detailOpt.isEmpty()) return Optional.<TitleDetail>empty();
            Object[] row = detailOpt.get();
            long tid = (long) row[0];
            Long primaryId = (Long) row[4];

            List<AssignedActress> actresses = h.createQuery("""
                    SELECT a.id AS id, a.canonical_name AS canonical_name, a.stage_name AS stage_name
                    FROM title_actresses ta
                    JOIN actresses a ON a.id = ta.actress_id
                    WHERE ta.title_id = :titleId
                    ORDER BY a.canonical_name COLLATE NOCASE ASC
                    """)
                    .bind("titleId", tid)
                    .map((rs, ctx) -> new AssignedActress(
                            rs.getLong("id"),
                            rs.getString("canonical_name"),
                            rs.getString("stage_name"),
                            primaryId != null && primaryId == rs.getLong("id")))
                    .list();

            return Optional.of(new TitleDetail(
                    tid,
                    (String) row[1],
                    basename((String) row[5]),
                    (String) row[2],
                    (String) row[3],
                    (String) row[5],
                    actresses
            ));
        });
    }

    @Override
    public boolean hasLocationInVolume(long titleId, String volumeId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) FROM title_locations
                WHERE title_id = :titleId AND volume_id = :volumeId
                """)
                .bind("titleId", titleId)
                .bind("volumeId", volumeId)
                .mapTo(Integer.class)
                .one() > 0);
    }

    @Override
    public long createDraftActress(Handle h, String canonicalName) {
        return h.createUpdate("""
                INSERT INTO actresses (canonical_name, tier, first_seen_at, needs_profiling)
                VALUES (:name, 'LIBRARY', :date, 1)
                """)
                .bind("name", canonicalName)
                .bind("date", LocalDate.now().toString())
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one();
    }

    @Override
    public void replaceActresses(long titleId, List<Long> actressIds, long primaryActressId) {
        jdbi.useTransaction(h -> replaceActressesInTx(h, titleId, actressIds, primaryActressId));
    }

    /** Package-private so the service layer can run this inside a larger transaction. */
    public void replaceActressesInTx(Handle h, long titleId, List<Long> actressIds, long primaryActressId) {
        h.createUpdate("DELETE FROM title_actresses WHERE title_id = :titleId")
                .bind("titleId", titleId)
                .execute();
        for (long id : actressIds) {
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                    VALUES (:titleId, :actressId)
                    """)
                    .bind("titleId", titleId)
                    .bind("actressId", id)
                    .execute();
        }
        h.createUpdate("UPDATE titles SET actress_id = :primary WHERE id = :titleId")
                .bind("primary", primaryActressId)
                .bind("titleId", titleId)
                .execute();
    }

    @Override
    public <T> T inTransaction(Function<Handle, T> work) {
        return jdbi.inTransaction(work::apply);
    }

    @Override
    public void renameFolderInDb(long titleId, String volumeId, String oldFolderPath, String newFolderPath) {
        jdbi.useTransaction(h -> {
            h.createUpdate("""
                    UPDATE title_locations SET path = :newPath
                    WHERE title_id = :titleId AND volume_id = :volumeId AND path = :oldPath
                    """)
                    .bind("newPath", newFolderPath)
                    .bind("oldPath", oldFolderPath)
                    .bind("titleId", titleId)
                    .bind("volumeId", volumeId)
                    .execute();
            // Rewrite video paths that sit under the old folder.
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
    public List<OtherLocation> findOtherLocations(long titleId, String excludeVolumeId, String excludePath) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT volume_id, path FROM title_locations
                WHERE title_id = :titleId
                  AND NOT (volume_id = :vol AND path = :path)
                ORDER BY volume_id, path
                """)
                .bind("titleId", titleId)
                .bind("vol", excludeVolumeId)
                .bind("path", excludePath)
                .map((rs, ctx) -> new OtherLocation(rs.getString("volume_id"), rs.getString("path")))
                .list());
    }

    @Override
    public Optional<String> findActressCanonicalName(long actressId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT canonical_name FROM actresses WHERE id = :id")
                .bind("id", actressId)
                .mapTo(String.class)
                .findFirst());
    }

    private static String basename(String path) {
        if (path == null || path.isEmpty()) return "";
        int idx = path.lastIndexOf('/');
        if (idx < 0) idx = path.lastIndexOf('\\');
        return idx < 0 ? path : path.substring(idx + 1);
    }
}
