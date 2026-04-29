package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.FilmographyMeta;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Forces a fresh HTTP filmography fetch for one actress, bypassing both L1 and L2 caches.
 * Updates both cache levels with the result.
 */
public class RefreshFilmographyTool implements Tool {

    private final JavdbSlugResolver resolver;

    public RefreshFilmographyTool(JavdbSlugResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name()        { return "refresh_filmography"; }
    @Override public String description() {
        return "Force a fresh HTTP fetch of an actress's javdb filmography, bypassing L1 and L2 caches. "
             + "Returns the updated entry count and metadata.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_slug", "string", "Javdb actress slug (e.g. 'J9dd').")
                .require("actress_slug")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String actressSlug = Schemas.requireString(args, "actress_slug");

        // Evict both L1 and L2 so filmographyOf() is forced to re-fetch over HTTP.
        resolver.evictL1(actressSlug);
        resolver.filmographyRepo().evict(actressSlug);

        Map<String, String> codeToSlug = resolver.filmographyOf(actressSlug);

        Optional<FilmographyMeta> meta = resolver.filmographyRepo().findMeta(actressSlug);
        String fetchedAt    = meta.map(FilmographyMeta::fetchedAt).orElse(null);
        int    pageCount    = meta.map(FilmographyMeta::pageCount).orElse(0);
        int    driftCount   = meta.map(FilmographyMeta::lastDriftCount).orElse(0);
        String fetchStatus  = meta.map(FilmographyMeta::lastFetchStatus).orElse(null);

        return new Result(actressSlug, codeToSlug.size(), fetchedAt, pageCount, driftCount, fetchStatus);
    }

    public record Result(
            String actressSlug,
            int entryCount,
            String fetchedAt,
            int pageCount,
            int lastDriftCount,
            String lastFetchStatus
    ) {}
}
