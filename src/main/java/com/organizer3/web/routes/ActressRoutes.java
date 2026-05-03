package com.organizer3.web.routes;

import com.organizer3.web.ActressBrowseService;
import io.javalin.Javalin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All /api/actresses/* routes. Wired by {@link com.organizer3.web.WebServer}.
 */
public class ActressRoutes {

    private final ActressBrowseService actressBrowseService;

    public ActressRoutes(ActressBrowseService actressBrowseService) {
        this.actressBrowseService = actressBrowseService;
    }

    public void register(Javalin app) {
        app.get("/api/actresses/index", ctx ->
                ctx.json(actressBrowseService.findPrefixIndex()));

        app.get("/api/actresses/tier-counts", ctx -> {
            String prefix = ctx.queryParam("prefix");
            if (prefix == null || prefix.isBlank()) { ctx.status(400); return; }
            ctx.json(actressBrowseService.findTierCountsByPrefix(prefix));
        });

        app.get("/api/actresses/random", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(24);
            limit = Math.max(1, limit);
            ctx.json(actressBrowseService.findRandom(limit));
        });

        app.get("/api/actresses", ctx -> {
            String idsParam     = ctx.queryParam("ids");
            String prefix       = ctx.queryParam("prefix");
            String tier         = ctx.queryParam("tier");
            String volumesParam = ctx.queryParam("volumes");
            String studioGroup  = ctx.queryParam("studioGroup");
            String company      = ctx.queryParam("company");
            String all          = ctx.queryParam("all");
            String favorites    = ctx.queryParam("favorites");
            String bookmarks    = ctx.queryParam("bookmarks");
            String search       = ctx.queryParam("search");
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            if (idsParam != null && !idsParam.isBlank()) {
                List<Long> ids = List.of(idsParam.split(",")).stream()
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Long::parseLong).toList();
                ctx.json(actressBrowseService.findByIds(ids));
            } else if (search != null && !search.isBlank()) {
                if (search.trim().length() < 2) {
                    ctx.json(List.of());
                } else {
                    ctx.json(actressBrowseService.searchByNamePaged(search.trim(), offset, limit));
                }
            } else if (prefix != null && !prefix.isBlank()) {
                try {
                    ctx.json(actressBrowseService.findByPrefixPaged(prefix, tier, offset, limit));
                } catch (IllegalArgumentException e) {
                    ctx.status(400);
                }
            } else if (tier != null && !tier.isBlank()) {
                try {
                    ctx.json(actressBrowseService.findByTierPaged(tier, company, offset, limit));
                } catch (IllegalArgumentException e) {
                    ctx.status(400);
                }
            } else if (volumesParam != null && !volumesParam.isBlank()) {
                var volumeIds = List.of(volumesParam.split(","));
                ctx.json(actressBrowseService.findByVolumesPaged(volumeIds, company, offset, limit));
            } else if (studioGroup != null && !studioGroup.isBlank()) {
                ctx.json(actressBrowseService.findByStudioGroupPaged(studioGroup, company, offset, limit));
            } else if ("true".equals(all)) {
                ctx.json(actressBrowseService.findAllPaged(offset, limit));
            } else if ("true".equals(favorites)) {
                ctx.json(actressBrowseService.findFavoritesPaged(offset, limit));
            } else if ("true".equals(bookmarks)) {
                ctx.json(actressBrowseService.findBookmarksPaged(offset, limit));
            } else {
                ctx.status(400);
            }
        });

        app.get("/api/actresses/dashboard", ctx ->
                ctx.json(actressBrowseService.buildDashboard()));

        app.get("/api/actresses/spotlight", ctx -> {
            Long excludeId = null;
            String exclude = ctx.queryParam("exclude");
            if (exclude != null && !exclude.isBlank()) {
                try { excludeId = Long.parseLong(exclude.trim()); }
                catch (NumberFormatException e) { ctx.status(400); return; }
            }
            var result = actressBrowseService.getSpotlight(excludeId);
            if (result == null) ctx.status(204);
            else ctx.json(result);
        });

        app.get("/api/actresses/{id}", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            actressBrowseService.findById(id)
                    .ifPresentOrElse(ctx::json, () -> ctx.status(404));
        });

        app.put("/api/actresses/{id}/aliases", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            List<String> aliases;
            try {
                var body = ctx.bodyAsClass(Map.class);
                Object raw = body.get("aliases");
                aliases = raw instanceof List<?> list
                        ? list.stream().map(Object::toString).toList()
                        : List.of();
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Invalid request body"));
                return;
            }
            var result = actressBrowseService.updateAliases(id, aliases);
            if (result.ok()) {
                actressBrowseService.findById(id).ifPresentOrElse(ctx::json, () -> ctx.status(404));
            } else {
                Map<String, Object> body2 = new java.util.LinkedHashMap<>();
                body2.put("error", result.error());
                if (result.conflictActressId() != null) {
                    body2.put("conflictActressId", result.conflictActressId());
                    body2.put("conflictActressName", result.conflictActressName());
                    body2.put("conflictKind", result.conflictKind());
                }
                ctx.status(409).json(body2);
            }
        });

        app.get("/api/actresses/{id}/titles", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            String company   = ctx.queryParam("company");
            String tagsParam = ctx.queryParam("tags");
            List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                    ? List.of(tagsParam.split(","))
                    : List.of();
            String enrichTagIdsParam = ctx.queryParam("enrichmentTagIds");
            List<Long> enrichmentTagIds = (enrichTagIdsParam != null && !enrichTagIdsParam.isBlank())
                    ? Arrays.stream(enrichTagIdsParam.split(","))
                             .map(String::trim).filter(s -> !s.isEmpty())
                             .map(Long::parseLong)
                             .toList()
                    : List.of();
            String sortBy  = ctx.queryParam("sortBy");
            String sortDir = ctx.queryParam("sortDir");
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            ctx.json(actressBrowseService.findTitlesByActress(id, offset, limit, company, tags, enrichmentTagIds, sortBy, sortDir));
        });

        app.get("/api/actresses/{id}/tags", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            ctx.json(actressBrowseService.findTagsForActress(id));
        });

        app.get("/api/actresses/{id}/enrichment-tags", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            ctx.json(actressBrowseService.findEnrichmentTagsForActress(id));
        });

        app.post("/api/actresses/{id}/favorite", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            actressBrowseService.toggleFavorite(id).ifPresentOrElse(
                    s -> ctx.json(Map.of("id", s.id(),
                            "favorite", s.favorite(),
                            "bookmark", s.bookmark(),
                            "rejected", s.rejected())),
                    () -> ctx.status(404).json(Map.of("error", "Actress not found"))
            );
        });

        app.post("/api/actresses/{id}/bookmark", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            String valueParam = ctx.queryParam("value");
            Optional<ActressBrowseService.FlagState> result = valueParam != null
                    ? actressBrowseService.setBookmark(id, Boolean.parseBoolean(valueParam))
                    : actressBrowseService.toggleBookmark(id);
            result.ifPresentOrElse(
                    s -> ctx.json(Map.of("id", s.id(),
                            "favorite", s.favorite(),
                            "bookmark", s.bookmark(),
                            "rejected", s.rejected())),
                    () -> ctx.status(404).json(Map.of("error", "Actress not found"))
            );
        });

        app.post("/api/actresses/{id}/reject", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            actressBrowseService.toggleRejected(id).ifPresentOrElse(
                    s -> ctx.json(Map.of("id", s.id(),
                            "favorite", s.favorite(),
                            "bookmark", s.bookmark(),
                            "rejected", s.rejected())),
                    () -> ctx.status(404).json(Map.of("error", "Actress not found"))
            );
        });

        app.post("/api/actresses/{id}/visit", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            actressBrowseService.recordVisit(id).ifPresentOrElse(
                    stats -> ctx.json(Map.of(
                            "visitCount", stats.visitCount(),
                            "lastVisitedAt", stats.lastVisitedAt() != null ? stats.lastVisitedAt() : "")),
                    () -> ctx.status(404).json(Map.of("error", "Actress not found"))
            );
        });

        app.post("/api/actresses/{id}/stage-name/search", ctx -> {
            long id;
            try { id = Long.parseLong(ctx.pathParam("id")); }
            catch (NumberFormatException e) { ctx.status(400); return; }
            var result = actressBrowseService.searchStageName(id);
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("stageName", result.orElse(null));
            ctx.json(body);
        });
    }
}
