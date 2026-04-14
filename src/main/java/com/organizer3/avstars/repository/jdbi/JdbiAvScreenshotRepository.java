package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvVideoScreenshot;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class JdbiAvScreenshotRepository implements AvScreenshotRepository {

    private static final RowMapper<AvVideoScreenshot> MAPPER = (rs, ctx) -> AvVideoScreenshot.builder()
            .id(rs.getLong("id"))
            .avVideoId(rs.getLong("av_video_id"))
            .seq(rs.getInt("seq"))
            .path(rs.getString("path"))
            .build();

    private final Jdbi jdbi;

    @Override
    public List<AvVideoScreenshot> findByVideoId(long avVideoId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM av_video_screenshots WHERE av_video_id = :vid ORDER BY seq")
                        .bind("vid", avVideoId)
                        .map(MAPPER)
                        .list());
    }

    @Override
    public Map<Long, Integer> findFirstSeqByVideoIds(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) return Map.of();
        String placeholders = videoIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbi.withHandle(h -> {
            var query = h.createQuery(
                    "SELECT av_video_id, MIN(seq) AS min_seq FROM av_video_screenshots " +
                    "WHERE av_video_id IN (" + placeholders + ") GROUP BY av_video_id");
            for (int i = 0; i < videoIds.size(); i++) {
                query.bind(i, videoIds.get(i));
            }
            Map<Long, Integer> result = new HashMap<>();
            query.map((rs, ctx) -> Map.entry(rs.getLong("av_video_id"), rs.getInt("min_seq")))
                 .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    @Override
    public Map<Long, Integer> findCountsByVideoIds(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) return Map.of();
        String placeholders = videoIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbi.withHandle(h -> {
            var query = h.createQuery(
                    "SELECT av_video_id, COUNT(*) AS cnt FROM av_video_screenshots " +
                    "WHERE av_video_id IN (" + placeholders + ") GROUP BY av_video_id");
            for (int i = 0; i < videoIds.size(); i++) {
                query.bind(i, videoIds.get(i));
            }
            Map<Long, Integer> result = new HashMap<>();
            query.map((rs, ctx) -> Map.entry(rs.getLong("av_video_id"), rs.getInt("cnt")))
                 .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    @Override
    public int countByVideoId(long avVideoId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM av_video_screenshots WHERE av_video_id = :vid")
                        .bind("vid", avVideoId)
                        .mapTo(Integer.class)
                        .one());
    }

    @Override
    public void insert(long avVideoId, int seq, String path) {
        jdbi.useHandle(h ->
                h.execute(
                        "INSERT OR REPLACE INTO av_video_screenshots (av_video_id, seq, path) VALUES (?, ?, ?)",
                        avVideoId, seq, path));
    }

    @Override
    public void deleteByVideoId(long avVideoId) {
        jdbi.useHandle(h ->
                h.execute("DELETE FROM av_video_screenshots WHERE av_video_id = ?", avVideoId));
    }
}
