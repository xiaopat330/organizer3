package com.organizer3.web.routes;

import com.organizer3.model.WatchHistory;
import com.organizer3.repository.WatchHistoryRepository;
import io.javalin.Javalin;

import java.util.List;
import java.util.Map;

/** Watch-history read/write routes. */
public class WatchHistoryRoutes {

    private final WatchHistoryRepository repo;

    public WatchHistoryRoutes(WatchHistoryRepository repo) {
        this.repo = repo;
    }

    public void register(Javalin app) {
        app.post("/api/watch-history/{titleCode}", ctx -> {
            String titleCode = ctx.pathParam("titleCode");
            WatchHistory entry = repo.record(titleCode, java.time.LocalDateTime.now());
            ctx.json(Map.of("id", entry.getId(), "titleCode", entry.getTitleCode(),
                    "watchedAt", entry.getWatchedAt().toString()));
        });

        app.get("/api/watch-history", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
            List<WatchHistory> history = repo.findAll(limit);
            ctx.json(history.stream().map(e -> Map.of(
                    "id", e.getId(),
                    "titleCode", e.getTitleCode(),
                    "watchedAt", e.getWatchedAt().toString()
            )).toList());
        });

        app.get("/api/watch-history/{titleCode}", ctx -> {
            String titleCode = ctx.pathParam("titleCode");
            List<WatchHistory> history = repo.findByTitleCode(titleCode);
            ctx.json(history.stream().map(e -> Map.of(
                    "id", e.getId(),
                    "titleCode", e.getTitleCode(),
                    "watchedAt", e.getWatchedAt().toString()
            )).toList());
        });
    }
}
