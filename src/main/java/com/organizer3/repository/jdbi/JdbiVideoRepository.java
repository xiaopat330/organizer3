package com.organizer3.repository.jdbi;

import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class JdbiVideoRepository implements VideoRepository {

    private static final RowMapper<Video> MAPPER = (rs, ctx) -> Video.builder()
            .id(rs.getLong("id"))
            .titleId(rs.getLong("title_id"))
            .filename(rs.getString("filename"))
            .path(Path.of(rs.getString("path")))
            .lastSeenAt(LocalDate.parse(rs.getString("last_seen_at")))
            .build();

    private final Jdbi jdbi;

    public JdbiVideoRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Optional<Video> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM videos WHERE id = :id")
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst()
        );
    }

    @Override
    public List<Video> findByTitle(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM videos WHERE title_id = :titleId ORDER BY filename")
                        .bind("titleId", titleId)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public Video save(Video video) {
        return jdbi.withHandle(h -> {
            if (video.getId() == null) {
                long id = h.createUpdate("""
                                INSERT INTO videos (title_id, filename, path, last_seen_at)
                                VALUES (:titleId, :filename, :path, :lastSeenAt)
                                """)
                        .bind("titleId", video.getTitleId())
                        .bind("filename", video.getFilename())
                        .bind("path", video.getPath().toString())
                        .bind("lastSeenAt", video.getLastSeenAt().toString())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                return Video.builder().id(id).titleId(video.getTitleId()).filename(video.getFilename()).path(video.getPath()).lastSeenAt(video.getLastSeenAt()).build();
            } else {
                h.createUpdate("""
                                UPDATE videos SET
                                    title_id = :titleId, filename = :filename,
                                    path = :path, last_seen_at = :lastSeenAt
                                WHERE id = :id
                                """)
                        .bind("id", video.getId())
                        .bind("titleId", video.getTitleId())
                        .bind("filename", video.getFilename())
                        .bind("path", video.getPath().toString())
                        .bind("lastSeenAt", video.getLastSeenAt().toString())
                        .execute();
                return video;
            }
        });
    }

    @Override
    public void delete(long id) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM videos WHERE id = :id")
                        .bind("id", id)
                        .execute()
        );
    }

    @Override
    public void deleteByTitle(long titleId) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM videos WHERE title_id = :titleId")
                        .bind("titleId", titleId)
                        .execute()
        );
    }

    @Override
    public void deleteByVolume(String volumeId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM videos WHERE title_id IN (
                            SELECT id FROM titles WHERE volume_id = :volumeId
                        )""")
                        .bind("volumeId", volumeId)
                        .execute()
        );
    }

    @Override
    public void deleteByVolumeAndPartition(String volumeId, String partitionId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM videos WHERE title_id IN (
                            SELECT id FROM titles
                            WHERE volume_id = :volumeId AND partition_id = :partitionId
                        )""")
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .execute()
        );
    }
}
