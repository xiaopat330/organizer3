package com.organizer3.repository.jdbi;

import com.organizer3.model.WatchHistory;
import com.organizer3.repository.WatchHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
public class JdbiWatchHistoryRepository implements WatchHistoryRepository {

    private static final RowMapper<WatchHistory> MAPPER = (rs, ctx) -> WatchHistory.builder()
            .id(rs.getLong("id"))
            .titleCode(rs.getString("title_code"))
            .watchedAt(LocalDateTime.parse(rs.getString("watched_at")))
            .build();

    private final Jdbi jdbi;

    @Override
    public WatchHistory record(String titleCode, LocalDateTime watchedAt) {
        return jdbi.withHandle(h -> {
            long id = h.createUpdate("""
                            INSERT INTO watch_history (title_code, watched_at)
                            VALUES (:titleCode, :watchedAt)
                            """)
                    .bind("titleCode", titleCode)
                    .bind("watchedAt", watchedAt.toString())
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();
            return WatchHistory.builder()
                    .id(id)
                    .titleCode(titleCode)
                    .watchedAt(watchedAt)
                    .build();
        });
    }

    @Override
    public List<WatchHistory> findAll(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM watch_history ORDER BY watched_at DESC LIMIT :limit")
                        .bind("limit", limit)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public List<WatchHistory> findByTitleCode(String titleCode) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM watch_history WHERE title_code = :titleCode ORDER BY watched_at DESC")
                        .bind("titleCode", titleCode)
                        .map(MAPPER)
                        .list()
        );
    }

    @Override
    public Optional<LocalDateTime> lastWatchedAt(String titleCode) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT MAX(watched_at) FROM watch_history WHERE title_code = :titleCode")
                        .bind("titleCode", titleCode)
                        .mapTo(String.class)
                        .findFirst()
                        .map(LocalDateTime::parse)
        );
    }

    @Override
    public Map<String, WatchStats> findWatchStatsBatch(Collection<String> titleCodes) {
        if (titleCodes.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<String, WatchStats> result = new HashMap<>();
            h.createQuery("SELECT title_code, MAX(watched_at) AS last_watched, COUNT(*) AS watch_count FROM watch_history WHERE title_code IN (<codes>) GROUP BY title_code")
                    .bindList("codes", titleCodes.stream().toList())
                    .map((rs, ctx) -> {
                        result.put(rs.getString("title_code"),
                                new WatchStats(LocalDateTime.parse(rs.getString("last_watched")), rs.getInt("watch_count")));
                        return null;
                    })
                    .list();
            return result;
        });
    }

    @Override
    public void deleteByTitleCode(String titleCode) {
        jdbi.useHandle(h ->
                h.createUpdate("DELETE FROM watch_history WHERE title_code = :titleCode")
                        .bind("titleCode", titleCode)
                        .execute()
        );
    }
}
