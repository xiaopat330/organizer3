package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvVideoScreenshot;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;

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
