package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvVideoRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiAvVideoRepository implements AvVideoRepository {

    private static final RowMapper<AvVideo> MAPPER = (rs, ctx) -> AvVideo.builder()
            .id(rs.getLong("id"))
            .avActressId(rs.getLong("av_actress_id"))
            .volumeId(rs.getString("volume_id"))
            .relativePath(rs.getString("relative_path"))
            .filename(rs.getString("filename"))
            .extension(rs.getString("extension"))
            .sizeBytes(rs.getObject("size_bytes") != null ? rs.getLong("size_bytes") : null)
            .mtime(rs.getString("mtime"))
            .lastSeenAt(rs.getString("last_seen_at") != null
                    ? LocalDateTime.parse(rs.getString("last_seen_at")) : null)
            .addedDate(rs.getString("added_date"))
            .bucket(rs.getString("bucket"))
            .studio(rs.getString("studio"))
            .releaseDate(rs.getString("release_date"))
            .parsedTitle(rs.getString("parsed_title"))
            .resolution(rs.getString("resolution"))
            .codec(rs.getString("codec"))
            .tagsJson(rs.getString("tags_json"))
            .favorite(rs.getInt("favorite") == 1)
            .rejected(rs.getInt("rejected") == 1)
            .bookmark(rs.getInt("bookmark") == 1)
            .watched(rs.getInt("watched") == 1)
            .lastWatchedAt(rs.getString("last_watched_at"))
            .watchCount(rs.getInt("watch_count"))
            .build();

    private final Jdbi jdbi;

    @Override
    public Optional<AvVideo> findById(long id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_videos WHERE id = :id")
                        .bind("id", id)
                        .map(MAPPER)
                        .findFirst());
    }

    @Override
    public List<AvVideo> findByActress(long avActressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_videos WHERE av_actress_id = :id ORDER BY relative_path")
                        .bind("id", avActressId)
                        .map(MAPPER)
                        .list());
    }

    @Override
    public List<AvVideo> findByVolume(String volumeId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_videos WHERE volume_id = :volumeId ORDER BY relative_path")
                        .bind("volumeId", volumeId)
                        .map(MAPPER)
                        .list());
    }

    @Override
    public long upsert(AvVideo video) {
        return jdbi.withHandle(h -> {
            h.createUpdate("""
                    INSERT INTO av_videos (
                        av_actress_id, volume_id, relative_path, filename, extension,
                        size_bytes, mtime, last_seen_at, added_date, bucket
                    ) VALUES (
                        :actressId, :volumeId, :relativePath, :filename, :extension,
                        :sizeBytes, :mtime, :lastSeenAt, :addedDate, :bucket
                    ) ON CONFLICT(av_actress_id, relative_path) DO UPDATE SET
                        size_bytes   = excluded.size_bytes,
                        mtime        = excluded.mtime,
                        last_seen_at = excluded.last_seen_at
                    """)
                    .bind("actressId", video.getAvActressId())
                    .bind("volumeId", video.getVolumeId())
                    .bind("relativePath", video.getRelativePath())
                    .bind("filename", video.getFilename())
                    .bind("extension", video.getExtension())
                    .bind("sizeBytes", video.getSizeBytes())
                    .bind("mtime", video.getMtime())
                    .bind("lastSeenAt", video.getLastSeenAt() != null
                            ? video.getLastSeenAt().toString() : LocalDateTime.now().toString())
                    .bind("addedDate", video.getAddedDate())
                    .bind("bucket", video.getBucket())
                    .execute();

            return h.createQuery(
                    "SELECT id FROM av_videos WHERE av_actress_id = :actressId AND relative_path = :relativePath")
                    .bind("actressId", video.getAvActressId())
                    .bind("relativePath", video.getRelativePath())
                    .mapTo(Long.class)
                    .one();
        });
    }

    @Override
    public void deleteOrphanedByVolume(String volumeId, LocalDateTime syncStart) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        DELETE FROM av_videos
                        WHERE volume_id = :volumeId AND last_seen_at < :syncStart
                        """)
                        .bind("volumeId", volumeId)
                        .bind("syncStart", syncStart.toString())
                        .execute());
    }

    @Override
    public void toggleFavorite(long videoId, boolean favorite) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE av_videos SET favorite = :v WHERE id = :id")
                .bind("v", favorite ? 1 : 0).bind("id", videoId).execute());
    }

    @Override
    public void toggleBookmark(long videoId, boolean bookmark) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE av_videos SET bookmark = :v WHERE id = :id")
                .bind("v", bookmark ? 1 : 0).bind("id", videoId).execute());
    }

    @Override
    public void recordWatch(long videoId) {
        jdbi.useHandle(h -> h.createUpdate("""
                UPDATE av_videos SET
                    watched          = 1,
                    watch_count      = watch_count + 1,
                    last_watched_at  = :now
                WHERE id = :id
                """)
                .bind("now", LocalDateTime.now().toString())
                .bind("id", videoId).execute());
    }

    @Override
    public void updateParsedFields(long videoId, String studio, String releaseDate,
                                   String resolution, String codec, String tagsJson) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        UPDATE av_videos SET
                            studio       = :studio,
                            release_date = :releaseDate,
                            resolution   = :resolution,
                            codec        = :codec,
                            tags_json    = :tagsJson
                        WHERE id = :id
                        """)
                        .bind("studio", studio)
                        .bind("releaseDate", releaseDate)
                        .bind("resolution", resolution)
                        .bind("codec", codec)
                        .bind("tagsJson", tagsJson)
                        .bind("id", videoId)
                        .execute());
    }
}
