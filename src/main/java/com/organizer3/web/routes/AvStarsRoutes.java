package com.organizer3.web.routes;

import com.organizer3.avstars.iafd.IafdFetchException;
import com.organizer3.avstars.iafd.IafdSearchResult;
import com.organizer3.avstars.web.AvBrowseService;
import com.organizer3.utilities.avstars.AvStarsCatalogService;
import com.organizer3.utilities.avstars.IafdResolverService;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilities → AV Stars HTTP surface. Separate from {@link UtilitiesRoutes} so the Utilities
 * root stays readable — AV Stars has enough endpoints to warrant its own namespace.
 *
 * <p>Curation toggles (favorite, bookmark) ride the existing {@code /api/av/*} routes
 * unchanged; this class adds the Utilities-only capabilities: filtered listing,
 * technical summary, and the IAFD search surface used by the resolver picker.
 */
@Slf4j
public final class AvStarsRoutes {

    private final AvStarsCatalogService catalog;
    private final AvBrowseService browseService;
    private final IafdResolverService resolver;

    public AvStarsRoutes(AvStarsCatalogService catalog, AvBrowseService browseService,
                         IafdResolverService resolver) {
        this.catalog = catalog;
        this.browseService = browseService;
        this.resolver = resolver;
    }

    public void register(Javalin app) {
        app.get("/api/utilities/avstars/actresses", ctx -> {
            AvStarsCatalogService.Filter filter = parseFilter(ctx.queryParam("filter"));
            AvStarsCatalogService.Sort sort = parseSort(ctx.queryParam("sort"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("counts", catalog.counts());
            out.put("rows", catalog.list(filter, sort));
            ctx.json(out);
        });

        app.get("/api/utilities/avstars/actresses/{id}", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            var detail = browseService.getActressDetail(id);
            if (detail.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "no such AV actress: " + id));
                return;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("detail", detail.get());
            out.put("techSummary", catalog.techSummary(id));
            ctx.json(out);
        });

        app.post("/api/utilities/avstars/actresses/{id}/iafd/search", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String name = body != null && body.get("name") instanceof String s && !s.isBlank()
                    ? s
                    : null;
            if (name == null) {
                // Default to the actress's stage name
                var detail = browseService.getActressDetail(id);
                if (detail.isEmpty()) { ctx.status(404); ctx.json(Map.of("error", "unknown actress")); return; }
                name = detail.get().getStageName();
            }
            try {
                List<IafdSearchResult> candidates = resolver.search(name);
                ctx.json(Map.of("query", name, "candidates", candidates));
            } catch (IafdFetchException e) {
                log.warn("IAFD search failed for '{}'", name, e);
                ctx.status(502);
                ctx.json(Map.of("error", "IAFD fetch failed: " + e.getMessage()));
            }
        });
    }

    private static AvStarsCatalogService.Filter parseFilter(String raw) {
        if (raw == null || raw.isBlank()) return AvStarsCatalogService.Filter.ALL;
        try {
            return AvStarsCatalogService.Filter.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AvStarsCatalogService.Filter.ALL;
        }
    }

    private static AvStarsCatalogService.Sort parseSort(String raw) {
        if (raw == null || raw.isBlank()) return AvStarsCatalogService.Sort.VIDEO_COUNT_DESC;
        try {
            return AvStarsCatalogService.Sort.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AvStarsCatalogService.Sort.VIDEO_COUNT_DESC;
        }
    }
}
