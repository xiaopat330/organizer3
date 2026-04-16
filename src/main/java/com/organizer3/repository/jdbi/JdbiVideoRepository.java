package com.organizer3.repository.jdbi;

import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiVideoRepository implements VideoRepository {

    private static final RowMapper<Video> MAPPER = (rs, ctx) -> {
        long duration = rs.getLong("duration_sec");
        Long durationOrNull = rs.wasNull() ? null : duration;
        int width = rs.getInt("width");
        Integer widthOrNull = rs.wasNull() ? null : width;
        int height = rs.getInt("height");
        Integer heightOrNull = rs.wasNull() ? null : height;
        return Video.builder()
                .id(rs.getLong("id"))
                .titleId(rs.getLong("title_id"))
                .volumeId(rs.getString("volume_id"))
                .filename(rs.getString("filename"))
                .path(Path.of(rs.getString("path")))
                .lastSeenAt(LocalDate.parse(rs.getString("last_seen_at")))
                .durationSec(durationOrNull)
                .width(widthOrNull)
                .height(heightOrNull)
                .videoCodec(rs.getString("video_codec"))
                .audioCodec(rs.getString("audio_codec"))
                .container(rs.getString("container"))
                .build();
    };

    private final Jdbi jdbi;

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
                                INSERT INTO videos
                                    (title_id, volume_id, filename, path, last_seen_at,
                                     duration_sec, width, height, video_codec, audio_codec, container)
                                VALUES
                                    (:titleId, :volumeId, :filename, :path, :lastSeenAt,
                                     :durationSec, :width, :height, :videoCodec, :audioCodec, :container)
                                """)
                        .bind("titleId", video.getTitleId())
                        .bind("volumeId", video.getVolumeId())
                        .bind("filename", video.getFilename())
                        .bind("path", video.getPath().toString())
                        .bind("lastSeenAt", video.getLastSeenAt().toString())
                        .bind("durationSec", video.getDurationSec())
                        .bind("width", video.getWidth())
                        .bind("height", video.getHeight())
                        .bind("videoCodec", video.getVideoCodec())
                        .bind("audioCodec", video.getAudioCodec())
                        .bind("container", video.getContainer())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
                return video.toBuilder().id(id).build();
            } else {
                h.createUpdate("""
                                UPDATE videos SET
                                    title_id = :titleId, volume_id = :volumeId, filename = :filename,
                                    path = :path, last_seen_at = :lastSeenAt
                                WHERE id = :id
                                """)
                        .bind("id", video.getId())
                        .bind("titleId", video.getTitleId())
                        .bind("volumeId", video.getVolumeId())
                        .bind("filename", video.getFilename())
                        .bind("path", video.getPath().toString())
                        .bind("lastSeenAt", video.getLastSeenAt().toString())
                        .execute();
                return video;
            }
        });
    }

    @Override
    public void updateMetadata(long videoId, Long durationSec, Integer width, Integer height,
                               String videoCodec, String audioCodec, String container) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE videos SET
                            duration_sec = :durationSec,
                            width        = :width,
                            height       = :height,
                            video_codec  = :videoCodec,
                            audio_codec  = :audioCodec,
                            container    = :container
                        WHERE id = :id
                        """)
                        .bind("id", videoId)
                        .bind("durationSec", durationSec)
                        .bind("width", width)
                        .bind("height", height)
                        .bind("videoCodec", videoCodec)
                        .bind("audioCodec", audioCodec)
                        .bind("container", container)
                        .execute());
    }

    @Override
    public List<Video> findUnprobed(String volumeId, long fromIdExclusive, int limit) {
        return jdbi.withHandle(h -> {
            String sql = "SELECT * FROM videos WHERE duration_sec IS NULL AND id > :fromId"
                    + (volumeId != null ? " AND volume_id = :volumeId" : "")
                    + " ORDER BY id LIMIT :limit";
            var q = h.createQuery(sql).bind("fromId", fromIdExclusive).bind("limit", limit);
            if (volumeId != null) q.bind("volumeId", volumeId);
            return q.map(MAPPER).list();
        });
    }

    @Override
    public long countUnprobed(String volumeId) {
        return jdbi.withHandle(h -> {
            String sql = "SELECT COUNT(*) FROM videos WHERE duration_sec IS NULL"
                    + (volumeId != null ? " AND volume_id = :volumeId" : "");
            var q = h.createQuery(sql);
            if (volumeId != null) q.bind("volumeId", volumeId);
            return q.mapTo(Long.class).one();
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
                h.createUpdate("DELETE FROM videos WHERE volume_id = :volumeId")
                        .bind("volumeId", volumeId)
                        .execute()
        );
    }

    @Override
    public void deleteByVolumeAndPartition(String volumeId, String partitionId) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM videos WHERE volume_id = :volumeId
                        AND title_id IN (
                            SELECT DISTINCT title_id FROM title_locations
                            WHERE volume_id = :volumeId AND partition_id = :partitionId
                        )""")
                        .bind("volumeId", volumeId)
                        .bind("partitionId", partitionId)
                        .execute()
        );
    }
}
