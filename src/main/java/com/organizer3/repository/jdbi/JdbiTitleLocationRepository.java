package com.organizer3.repository.jdbi;

import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleLocationRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
public class JdbiTitleLocationRepository implements TitleLocationRepository {

    private static final RowMapper<TitleLocation> MAPPER = (rs, ctx) -> {
        String addedDateStr = rs.getString("added_date");
        return TitleLocation.builder()
                .id(rs.getLong("id"))
                .titleId(rs.getLong("title_id"))
                .volumeId(rs.getString("volume_id"))
                .partitionId(rs.getString("partition_id"))
                .path(Path.of(rs.getString("path")))
                .lastSeenAt(LocalDate.parse(rs.getString("last_seen_at")))
                .addedDate(addedDateStr != null ? LocalDate.parse(addedDateStr) : null)
                .build();
    };

    private final Jdbi jdbi;

    @Override
    public TitleLocation save(TitleLocation location) {
        return jdbi.withHandle(h -> {
            long id = h.createUpdate("""
                            INSERT INTO title_locations
                                (title_id, volume_id, partition_id, path, last_seen_at, added_date)
                            VALUES (:titleId, :volumeId, :partitionId, :path, :lastSeenAt, :addedDate)
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
            return location.toBuilder().id(id).build();
        });
    }

    @Override
    public List<TitleLocation> findByTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM title_locations WHERE title_id = :titleId ORDER BY volume_id, path")
                        .bind("titleId", titleId)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<TitleLocation> findByTitleIds(List<Long> titleIds) {
        if (titleIds.isEmpty()) return List.of();
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM title_locations WHERE title_id IN (<titleIds>) ORDER BY title_id, volume_id, path")
                        .bindList("titleIds", titleIds)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<TitleLocation> findByVolume(String volumeId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM title_locations WHERE volume_id = :volumeId ORDER BY path")
                        .bind("volumeId", volumeId)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public void deleteByVolume(String volumeId) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM title_locations WHERE volume_id = :volumeId")
                        .bind("volumeId", volumeId)
                        .execute()
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
}
