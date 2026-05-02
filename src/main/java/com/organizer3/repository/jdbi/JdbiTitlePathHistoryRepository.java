package com.organizer3.repository.jdbi;

import com.organizer3.model.TitlePathHistoryEntry;
import com.organizer3.repository.TitlePathHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiTitlePathHistoryRepository implements TitlePathHistoryRepository {

    private static final RowMapper<TitlePathHistoryEntry> MAPPER = (rs, ctx) ->
            TitlePathHistoryEntry.builder()
                    .id(rs.getLong("id"))
                    .titleId(rs.getLong("title_id"))
                    .volumeId(rs.getString("volume_id"))
                    .partitionId(rs.getString("partition_id"))
                    .path(rs.getString("path"))
                    .firstSeenAt(rs.getString("first_seen_at"))
                    .lastSeenAt(rs.getString("last_seen_at"))
                    .build();

    private final Jdbi jdbi;

    @Override
    public void recordPath(long titleId, String volumeId, String partitionId,
                           String path, String nowIso) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_path_history
                    (title_id, volume_id, partition_id, path, first_seen_at, last_seen_at)
                VALUES (:titleId, :volumeId, :partitionId, :path, :now, :now)
                ON CONFLICT (volume_id, partition_id, path)
                    DO UPDATE SET last_seen_at = :now
                """)
                .bind("titleId",     titleId)
                .bind("volumeId",    volumeId)
                .bind("partitionId", partitionId)
                .bind("path",        path)
                .bind("now",         nowIso)
                .execute());
    }

    @Override
    public Optional<Long> findByPath(String volumeId, String partitionId, String path) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT tph.title_id
                        FROM title_path_history tph
                        WHERE tph.volume_id    = :volumeId
                          AND tph.partition_id = :partitionId
                          AND tph.path         = :path
                          AND EXISTS (SELECT 1 FROM titles WHERE id = tph.title_id)
                        ORDER BY tph.last_seen_at DESC
                        LIMIT 1
                        """)
                        .bind("volumeId",    volumeId)
                        .bind("partitionId", partitionId)
                        .bind("path",        path)
                        .mapTo(Long.class)
                        .findOne());
    }

    @Override
    public List<TitlePathHistoryEntry> listForTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT *
                        FROM title_path_history
                        WHERE title_id = :titleId
                        ORDER BY last_seen_at DESC
                        """)
                        .bind("titleId", titleId)
                        .map(MAPPER)
                        .list());
    }
}
