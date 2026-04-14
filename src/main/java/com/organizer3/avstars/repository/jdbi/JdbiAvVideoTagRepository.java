package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.repository.AvVideoTagRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class JdbiAvVideoTagRepository implements AvVideoTagRepository {

    private final Jdbi jdbi;

    @Override
    public Map<Long, List<String>> findTagSlugsByVideoIds(List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) return Map.of();
        // Build IN clause with positional placeholders
        String placeholders = videoIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
        String sql = "SELECT av_video_id, tag_slug FROM av_video_tags WHERE av_video_id IN (" + placeholders + ") ORDER BY av_video_id, tag_slug";
        Map<Long, List<String>> result = new HashMap<>();
        jdbi.useHandle(h -> {
            var query = h.createQuery(sql);
            for (int i = 0; i < videoIds.size(); i++) {
                query.bind(i, videoIds.get(i));
            }
            query.map((rs, ctx) -> Map.entry(rs.getLong("av_video_id"), rs.getString("tag_slug")))
                    .forEach(e -> result.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        });
        return result;
    }

    @Override
    public List<String> findTopTagSlugsForActress(long avActressId, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT vt.tag_slug, COUNT(*) AS cnt
                        FROM av_video_tags vt
                        JOIN av_videos v ON v.id = vt.av_video_id
                        WHERE v.av_actress_id = :actressId
                        GROUP BY vt.tag_slug
                        ORDER BY cnt DESC
                        LIMIT :limit""")
                        .bind("actressId", avActressId)
                        .bind("limit", limit)
                        .mapTo(String.class)
                        .list());
    }

    @Override
    public void insertVideoTag(long avVideoId, String tagSlug, String source) {
        jdbi.useHandle(h ->
                h.createUpdate("""
                        INSERT OR IGNORE INTO av_video_tags (av_video_id, tag_slug, source)
                        VALUES (:videoId, :tagSlug, :source)""")
                        .bind("videoId", avVideoId)
                        .bind("tagSlug", tagSlug)
                        .bind("source", source)
                        .execute());
    }

    @Override
    public void deleteByVideoId(long avVideoId) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM av_video_tags WHERE av_video_id = :videoId")
                        .bind("videoId", avVideoId)
                        .execute());
    }

    @Override
    public void deleteByVideoIdAndSource(long avVideoId, String source) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM av_video_tags WHERE av_video_id = :videoId AND source = :source")
                        .bind("videoId", avVideoId)
                        .bind("source", source)
                        .execute());
    }

    @Override
    public List<String> getAllTagsJson() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT tags_json FROM av_videos WHERE tags_json IS NOT NULL")
                        .mapTo(String.class)
                        .list());
    }

    @Override
    public List<String[]> getAllVideoIdAndTagsJson() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT id, tags_json FROM av_videos WHERE tags_json IS NOT NULL")
                        .map((rs, ctx) -> new String[]{rs.getString("id"), rs.getString("tags_json")})
                        .list());
    }
}
