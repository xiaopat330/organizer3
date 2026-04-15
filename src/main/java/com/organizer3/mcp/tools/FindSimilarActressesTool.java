package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Find pairs of actresses whose names (canonical or alias) are within a small edit
 * distance of each other — candidates for misspelling-driven duplicate entries.
 *
 * <p>Compares every (canonical + alias) name against every other, bucketed by length so
 * the pairwise check only runs where the distance could plausibly be below the threshold.
 * Pairs are only emitted when the two names belong to <em>different</em> actress ids —
 * aliases pointing at the same canonical are already-resolved, not anomalous.
 */
public class FindSimilarActressesTool implements Tool {

    private static final int DEFAULT_MAX_DISTANCE = 2;
    private static final int DEFAULT_MIN_LENGTH   = 4;
    private static final int DEFAULT_LIMIT        = 100;
    private static final int MAX_LIMIT            = 1000;

    private final ActressRepository actressRepo;

    public FindSimilarActressesTool(ActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    @Override public String name()        { return "find_similar_actresses"; }
    @Override public String description() {
        return "Find pairs of actresses whose names (canonical or alias) are within a small edit distance of each other. "
             + "Surfaces misspelling-driven duplicate entries — e.g. 'Yua Mikami' vs 'Yua Mikarni'. Only reports pairs "
             + "with different actress ids (aliases pointing at the same canonical are already resolved).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("max_distance", "integer", "Maximum Levenshtein distance to consider similar. Default 2.", DEFAULT_MAX_DISTANCE)
                .prop("min_length",   "integer", "Ignore names shorter than this — short names are noise. Default 4.", DEFAULT_MIN_LENGTH)
                .prop("limit",        "integer", "Maximum number of pairs to return. Default 100, max 1000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int maxDist  = Math.max(1, Schemas.optInt(args, "max_distance", DEFAULT_MAX_DISTANCE));
        int minLen   = Math.max(1, Schemas.optInt(args, "min_length", DEFAULT_MIN_LENGTH));
        int limit    = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        List<NameEntry> names = collectNames(minLen);
        names.sort(Comparator.comparingInt(n -> n.normalized.length()));

        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < names.size() && pairs.size() < limit; i++) {
            NameEntry a = names.get(i);
            int aLen = a.normalized.length();
            for (int j = i + 1; j < names.size(); j++) {
                NameEntry b = names.get(j);
                if (b.normalized.length() - aLen > maxDist) break; // length-bucket prune
                if (a.actressId == b.actressId) continue;
                if (a.normalized.equals(b.normalized)) continue; // exact tie between distinct actresses is its own signal but not spelling
                int d = levenshtein(a.normalized, b.normalized, maxDist);
                if (d >= 0 && d <= maxDist) {
                    pairs.add(new Pair(
                            new Side(a.actressId, a.display, a.isAlias),
                            new Side(b.actressId, b.display, b.isAlias),
                            d));
                    if (pairs.size() >= limit) break;
                }
            }
        }
        return new Result(pairs.size(), pairs);
    }

    private List<NameEntry> collectNames(int minLen) {
        List<NameEntry> out = new ArrayList<>();
        for (Actress a : actressRepo.findAll()) {
            addIfEligible(out, a.getId(), a.getCanonicalName(), false, minLen);
            for (ActressAlias alias : actressRepo.findAliases(a.getId())) {
                addIfEligible(out, a.getId(), alias.aliasName(), true, minLen);
            }
        }
        return out;
    }

    private static void addIfEligible(List<NameEntry> out, long actressId, String name, boolean isAlias, int minLen) {
        if (name == null) return;
        String norm = name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (norm.length() < minLen) return;
        out.add(new NameEntry(actressId, name, norm, isAlias));
    }

    /**
     * Levenshtein with early-exit: returns -1 if distance certainly exceeds {@code threshold}.
     */
    static int levenshtein(String a, String b, int threshold) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > threshold) return -1;
        if (n == 0) return m;
        if (m == 0) return n;

        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (curr[j] < rowMin) rowMin = curr[j];
            }
            if (rowMin > threshold) return -1;
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private record NameEntry(long actressId, String display, String normalized, boolean isAlias) {}

    public record Side(long actressId, String name, boolean isAlias) {}
    public record Pair(Side a, Side b, int distance) {}
    public record Result(int count, List<Pair> pairs) {}
}
