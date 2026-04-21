package com.organizer3.web.routes;

import com.organizer3.repository.TitleRepository;
import com.organizer3.web.SearchService;
import com.organizer3.web.TitleBrowseService;
import com.organizer3.web.TitleSummary;
import io.javalin.Javalin;

import java.util.List;
import java.util.Map;

/**
 * Federated search and title-code lookup. /api/search, /api/titles/by-code*.
 * {@code titleRepo} and {@code browseService} may be null; routes degrade.
 */
public class SearchRoutes {

    private final SearchService searchService;
    private final TitleRepository titleRepo;
    private final TitleBrowseService browseService;

    public SearchRoutes(SearchService searchService, TitleRepository titleRepo, TitleBrowseService browseService) {
        this.searchService = searchService;
        this.titleRepo = titleRepo;
        this.browseService = browseService;
    }

    public void register(Javalin app) {
        app.get("/api/search", ctx -> {
            String q = ctx.queryParam("q");
            if (q == null || q.isBlank()) {
                ctx.json(Map.of("actresses", List.of(), "titles", List.of(),
                        "labels", List.of(), "companies", List.of()));
                return;
            }
            String matchMode = ctx.queryParam("matchMode");
            boolean startsWith = "startsWith".equals(matchMode);
            boolean includeAv     = "true".equals(ctx.queryParam("includeAv"));
            boolean includeSparse = "true".equals(ctx.queryParam("includeSparse"));
            ctx.json(searchService.search(q.trim(), startsWith, includeAv, includeSparse));
        });

        app.get("/api/titles/by-code-prefix", ctx -> {
            String prefix = ctx.queryParam("prefix");
            if (prefix == null || prefix.isBlank()) { ctx.json(List.of()); return; }
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(11);
            limit = Math.max(1, Math.min(limit, 11));
            ctx.json(searchService.searchByCodePrefix(prefix.trim().toUpperCase(), limit));
        });

        app.get("/api/titles/by-code/{code}", ctx -> {
            String code = ctx.pathParam("code").toUpperCase();
            if (titleRepo == null) { ctx.status(503); return; }
            if (!titleRepo.findByCode(code).isPresent()) { ctx.status(404); return; }
            if (browseService != null) {
                List<TitleSummary> hits = browseService.searchByCodePaged(code, 0, 10);
                TitleSummary exact = hits.stream()
                        .filter(ts -> code.equals(ts.getCode()))
                        .findFirst()
                        .orElse(null);
                if (exact != null) { ctx.json(exact); return; }
            }
            titleRepo.findByCode(code)
                    .ifPresent(t -> ctx.json(Map.of("id", t.getId(), "code", t.getCode())));
        });
    }
}
