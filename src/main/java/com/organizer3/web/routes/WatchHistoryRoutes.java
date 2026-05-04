package com.organizer3.web.routes;

import com.organizer3.model.WatchHistory;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.WatchHistoryRepository;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/** Watch-history read/write routes. */
@Slf4j
public class WatchHistoryRoutes {

    private final WatchHistoryRepository repo;
    private final TitleRepository titleRepo;

    public WatchHistoryRoutes(WatchHistoryRepository repo, TitleRepository titleRepo) {
        this.repo = repo;
        this.titleRepo = titleRepo;
    }

    public void register(Javalin app) {
        app.post("/api/watch-history/{titleCode}", ctx -> {
            String titleCode = ctx.pathParam("titleCode");
            if (titleCode.isBlank()) {
                ctx.status(400).result("titleCode must not be blank");
                return;
            }
            if (titleRepo.findByCode(titleCode).isEmpty()) {
                ctx.status(404).result("Title not found: " + titleCode);
                return;
            }
            WatchHistory entry = repo.record(titleCode, java.time.LocalDateTime.now());
            log.info("Watch recorded — code={} watchedAt={}", titleCode, entry.getWatchedAt());
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
