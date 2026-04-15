package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts across the main entity tables, plus per-volume title location counts.
 *
 * <p>Backed by a handful of fixed-shape aggregate queries through the shared Jdbi handle.
 * The numbers are approximate-but-close — they don't use a single snapshot transaction,
 * so a sync running concurrently will produce inconsistent counts. Good enough for
 * orientation; {@code sql_query} is the right tool for exact point-in-time answers.
 */
public class GetStatsTool implements Tool {

    private final Jdbi jdbi;

    public GetStatsTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "get_stats"; }
    @Override public String description() { return "Counts of titles, actresses, aliases, videos, favorites, plus per-volume title locations."; }
    @Override public JsonNode inputSchema() { return Schemas.empty(); }

    @Override
    public Object call(JsonNode args) {
        return jdbi.withHandle(h -> {
            long titles    = one(h, "SELECT COUNT(*) FROM titles");
            long actresses = one(h, "SELECT COUNT(*) FROM actresses");
            long aliases   = one(h, "SELECT COUNT(*) FROM actress_aliases");
            long videos    = one(h, "SELECT COUNT(*) FROM videos");
            long favorites = one(h, "SELECT COUNT(*) FROM actresses WHERE favorite = 1");
            long rejected  = one(h, "SELECT COUNT(*) FROM actresses WHERE rejected = 1");
            long locations = one(h, "SELECT COUNT(*) FROM title_locations");

            Map<String, Long> byVolume = new LinkedHashMap<>();
            h.createQuery("SELECT volume_id, COUNT(*) AS n FROM title_locations GROUP BY volume_id ORDER BY volume_id")
                    .map((rs, ctx) -> Map.entry(rs.getString("volume_id"), rs.getLong("n")))
                    .forEach(e -> byVolume.put(e.getKey(), e.getValue()));

            return new Stats(titles, actresses, aliases, videos, favorites, rejected, locations, byVolume);
        });
    }

    private static long one(org.jdbi.v3.core.Handle h, String sql) {
        return h.createQuery(sql).mapTo(Long.class).one();
    }

    public record Stats(
            long titles,
            long actresses,
            long aliases,
            long videos,
            long favoriteActresses,
            long rejectedActresses,
            long titleLocations,
            Map<String, Long> titleLocationsByVolume
    ) {}
}
