package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

/**
 * Evicts the cached filmography for one actress from both L1 (in-process map) and L2 (SQLite).
 * A no-op if the actress has no cached data.
 */
public class EvictFilmographyTool implements Tool {

    private final JavdbSlugResolver resolver;

    public EvictFilmographyTool(JavdbSlugResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name()        { return "evict_filmography"; }
    @Override public String description() {
        return "Evict the cached filmography for one actress from both L1 (in-process) and L2 (SQLite). "
             + "The next resolve call for this actress will trigger a fresh HTTP fetch.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_slug", "string", "Javdb actress slug to evict.")
                .require("actress_slug")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String actressSlug = Schemas.requireString(args, "actress_slug");

        boolean hadL2 = resolver.filmographyRepo().findMeta(actressSlug).isPresent();

        resolver.evictL1(actressSlug);
        resolver.filmographyRepo().evict(actressSlug);

        return new Result(actressSlug, hadL2);
    }

    public record Result(String actressSlug, boolean wasPresent) {}
}
