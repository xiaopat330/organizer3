package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.ActressRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Return actresses whose total title count is at-or-below a small threshold (default: 1).
 *
 * <p>Useful for pairing with {@link FindSimilarActressesTool} — a single-title "actress"
 * whose name is within edit distance of a well-populated actress is almost certainly a
 * misspelling. On its own, this tool is a low-signal listing; its value is as a filter
 * for other tools. Rejected actresses are excluded (they're already intentionally filtered
 * out of the collection view).
 */
public class FindLoneTitlesTool implements Tool {

    private static final int DEFAULT_MAX_TITLES = 1;
    private static final int DEFAULT_LIMIT      = 200;
    private static final int MAX_LIMIT          = 5000;

    private final ActressRepository actressRepo;

    public FindLoneTitlesTool(ActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    @Override public String name()        { return "find_lone_titles"; }
    @Override public String description() {
        return "List actresses whose total title count is at-or-below a threshold (default 1). "
             + "A candidate pool for misspelling detection when paired with find_similar_actresses.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("max_titles", "integer", "Include actresses with this many titles or fewer. Default 1.", DEFAULT_MAX_TITLES)
                .prop("limit",      "integer", "Maximum actresses to return. Default 200, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int maxTitles = Math.max(0, Schemas.optInt(args, "max_titles", DEFAULT_MAX_TITLES));
        int limit     = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        Map<Long, Integer> counts = actressRepo.countAllTitlesByActress();
        List<Row> rows = new ArrayList<>();
        for (var a : actressRepo.findAll()) {
            if (a.isRejected()) continue;
            int n = counts.getOrDefault(a.getId(), 0);
            if (n > maxTitles) continue;
            rows.add(new Row(a.getId(), a.getCanonicalName(), n,
                    a.getTier() == null ? null : a.getTier().name(),
                    a.isFavorite(), a.isBookmark()));
            if (rows.size() >= limit) break;
        }
        return new Result(rows.size(), rows);
    }

    public record Row(long actressId, String canonicalName, int titleCount, String tier,
                      boolean favorite, boolean bookmark) {}
    public record Result(int count, List<Row> actresses) {}
}
