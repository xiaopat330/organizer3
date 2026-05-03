package com.organizer3.web.routes;

import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import com.organizer3.web.ActressBrowseService;
import com.organizer3.web.TagCatalogLoader;
import com.organizer3.web.TitleBrowseService;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Title-browse routes: /api/titles/*, /api/labels/*, /api/tags, /api/tools/*,
 * /api/studio-groups/*, /api/pool/*, /api/collections/*, /api/companies,
 * plus mutation routes on /api/titles/{code}/visit|favorite|bookmark.
 *
 * {@code actressBrowseService} and {@code titleRepo} may be null; the
 * corresponding routes are skipped when the dep is absent.
 */
@Slf4j
public class TitleRoutes {

    private final TitleBrowseService browseService;
    private final ActressBrowseService actressBrowseService;
    private final TitleRepository titleRepo;

    public TitleRoutes(TitleBrowseService browseService,
                       ActressBrowseService actressBrowseService,
                       TitleRepository titleRepo) {
        this.browseService = browseService;
        this.actressBrowseService = actressBrowseService;
        this.titleRepo = titleRepo;
    }

    public void register(Javalin app) {
        app.get("/api/titles", ctx -> {
            String search              = ctx.queryParam("search");
            String favorites           = ctx.queryParam("favorites");
            String bookmarks           = ctx.queryParam("bookmarks");
            String tagsParam           = ctx.queryParam("tags");
            String enrichTagIdsParam   = ctx.queryParam("enrichmentTagIds");
            String codeParam           = ctx.queryParam("code");
            String company             = ctx.queryParam("company");
            String sort                = ctx.queryParam("sort");
            String order               = ctx.queryParam("order");
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            boolean hasEnrichTags = enrichTagIdsParam != null && !enrichTagIdsParam.isBlank();
            if (search != null && !search.isBlank()) {
                ctx.json(browseService.searchByCodePaged(search.trim(), offset, limit));
            } else if ("true".equals(favorites)) {
                ctx.json(browseService.findFavoritesPaged(offset, limit));
            } else if ("true".equals(bookmarks)) {
                ctx.json(browseService.findBookmarksPaged(offset, limit));
            } else if (codeParam != null || company != null || sort != null || order != null
                       || (tagsParam != null && !tagsParam.isBlank()) || hasEnrichTags) {
                List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                        ? List.of(tagsParam.split(",")) : List.of();
                List<Long> enrichmentTagIds = (enrichTagIdsParam != null && !enrichTagIdsParam.isBlank())
                        ? java.util.Arrays.stream(enrichTagIdsParam.split(","))
                            .map(String::trim).filter(s -> !s.isEmpty())
                            .map(Long::parseLong).toList()
                        : List.of();
                ctx.json(browseService.findLibraryPaged(codeParam, company, tags, enrichmentTagIds, sort, order, offset, limit));
            } else {
                ctx.json(browseService.findRecent(offset, limit));
            }
        });

        app.get("/api/titles/tag-counts", ctx -> {
            long totalTitles = browseService.countAll();
            Map<String, Long> counts = browseService.getTagCounts();
            ctx.json(Map.of("totalTitles", totalTitles, "counts", counts));
        });

        app.get("/api/labels/autocomplete", ctx -> {
            String prefix = ctx.queryParam("prefix");
            ctx.json(browseService.labelAutocomplete(prefix));
        });

        app.get("/api/tags", ctx -> ctx.json(new TagCatalogLoader().load()));
        app.get("/api/tools/volumes", ctx -> ctx.json(browseService.listVolumes()));
        app.get("/api/tools/duplicates", ctx -> {
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(50);
            String volumeId = ctx.queryParam("volumeId");
            offset = Math.max(offset, 0);
            ctx.json(browseService.findDuplicatesPaged(offset, limit, volumeId));
        });
        app.get("/api/titles/labels",  ctx -> ctx.json(browseService.listLabels()));
        app.get("/api/titles/studios", ctx -> ctx.json(browseService.listStudioGroups()));
        app.get("/api/studio-groups/{slug}/companies", ctx -> {
            String slug = ctx.pathParam("slug");
            ctx.json(actressBrowseService.listGroupCompaniesByTitleCount(slug));
        });
        app.get("/api/titles/top-actresses", ctx -> {
            String labelsParam = ctx.queryParam("labels");
            if (labelsParam == null || labelsParam.isBlank()) { ctx.json(List.of()); return; }
            List<String> labels = List.of(labelsParam.split(","));
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
            ctx.json(browseService.topActressesByLabels(labels, Math.min(limit, 50)));
        });
        app.get("/api/titles/newest-actresses", ctx -> {
            String labelsParam = ctx.queryParam("labels");
            if (labelsParam == null || labelsParam.isBlank()) { ctx.json(List.of()); return; }
            List<String> labels = List.of(labelsParam.split(","));
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
            ctx.json(browseService.newestActressesByLabels(labels, Math.min(limit, 50)));
        });

        app.get("/api/titles/dashboard", ctx ->
                ctx.json(browseService.buildDashboard()));

        app.get("/api/titles/spotlight", ctx -> {
            String exclude = ctx.queryParam("exclude");
            var result = browseService.getSpotlight(exclude);
            if (result == null) ctx.status(204);
            else ctx.json(result);
        });

        app.get("/api/titles/random", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(24);
            limit = Math.max(1, limit);
            ctx.json(browseService.findRandom(limit));
        });

        app.get("/api/queues/{volumeId}/titles", ctx -> {
            String volumeId = ctx.pathParam("volumeId");
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            ctx.json(browseService.findByVolumeQueue(volumeId, offset, limit));
        });

        app.get("/api/pool/{volumeId}/titles", ctx -> {
            String volumeId = ctx.pathParam("volumeId");
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            String company   = ctx.queryParam("company");
            String tagsParam = ctx.queryParam("tags");
            List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                    ? List.of(tagsParam.split(",")) : List.of();
            if ((company != null && !company.isBlank()) || !tags.isEmpty()) {
                ctx.json(browseService.findByVolumePartitionFiltered(volumeId, "pool", company, tags, offset, limit));
            } else {
                ctx.json(browseService.findByVolumePartition(volumeId, "pool", offset, limit));
            }
        });

        app.get("/api/pool/{volumeId}/tags", ctx -> {
            String volumeId = ctx.pathParam("volumeId");
            ctx.json(browseService.findTagsForPool(volumeId));
        });

        app.get("/api/collections/titles", ctx -> {
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            int limit  = ctx.queryParamAsClass("limit",  Integer.class).getOrDefault(24);
            offset = Math.max(offset, 0);
            limit  = Math.max(1, limit);
            String company   = ctx.queryParam("company");
            String tagsParam = ctx.queryParam("tags");
            List<String> tags = (tagsParam != null && !tagsParam.isBlank())
                    ? List.of(tagsParam.split(",")) : List.of();
            if ((company != null && !company.isBlank()) || !tags.isEmpty()) {
                ctx.json(browseService.findByVolumePagedFiltered("collections", company, tags, offset, limit));
            } else {
                ctx.json(browseService.findByVolumePaged("collections", offset, limit));
            }
        });

        app.get("/api/collections/tags", ctx -> ctx.json(browseService.findTagsForCollections()));
        app.get("/api/companies", ctx -> ctx.json(browseService.listAllCompanies()));

        app.post("/api/titles/{code}/visit", ctx -> {
            String code = ctx.pathParam("code");
            browseService.recordVisit(code).ifPresentOrElse(
                    stats -> ctx.json(Map.of(
                            "visitCount", stats.visitCount(),
                            "lastVisitedAt", stats.lastVisitedAt() != null ? stats.lastVisitedAt() : "")),
                    () -> ctx.status(404).json(Map.of("error", "Title not found"))
            );
        });

        if (titleRepo != null) {
            app.post("/api/titles/{code}/favorite", ctx -> {
                String code = ctx.pathParam("code");
                Title title = titleRepo.findByCode(code).orElse(null);
                if (title == null) { ctx.status(404).json(Map.of("error", "Title not found")); return; }
                boolean newValue = !title.isFavorite();
                titleRepo.toggleFavorite(title.getId(), newValue);
                log.info("Title modified — code={} favorite={}", code, newValue);
                ctx.json(Map.of("code", code, "favorite", newValue));
            });

            app.post("/api/titles/{code}/bookmark", ctx -> {
                String code = ctx.pathParam("code");
                Title title = titleRepo.findByCode(code).orElse(null);
                if (title == null) { ctx.status(404).json(Map.of("error", "Title not found")); return; }
                String valueParam = ctx.queryParam("value");
                boolean newValue = valueParam != null ? Boolean.parseBoolean(valueParam) : !title.isBookmark();
                titleRepo.toggleBookmark(title.getId(), newValue);
                log.info("Title modified — code={} bookmark={}", code, newValue);
                ctx.json(Map.of("code", code, "bookmark", newValue));
            });
        }
    }
}
