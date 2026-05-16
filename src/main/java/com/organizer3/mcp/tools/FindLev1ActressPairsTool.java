package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Find pairs of actresses whose canonical names are within a small Levenshtein
 * distance of each other, intended for typo-conflated duplicate audits.
 *
 * <p>Differs from {@link FindSimilarActressesTool} in three ways that matter for
 * triage workflows:
 * <ul>
 *   <li>Only compares canonical names (no aliases) — aliases muddy the per-actress
 *       title-count signal we surface here.</li>
 *   <li>Supports a {@code first_token_must_match} prune that massively shrinks the
 *       candidate space when typos preserve the first-name token (very common).</li>
 *   <li>Returns per-side tier + title count and supports {@code tier_mismatch_only}
 *       and {@code min_titles_either} filters so the highest-signal pairs surface
 *       first (e.g. SUPERSTAR / LIBRARY collisions).</li>
 * </ul>
 *
 * <p>Supports substitutions, insertions, and deletions via standard Levenshtein,
 * so length-differing typos ("Ria Aisai" / "Ria Aise", "Ria Kashi" / "Ria Kashii")
 * are caught alongside same-length substitutions.
 */
public class FindLev1ActressPairsTool implements Tool {

    private static final int DEFAULT_MAX_DISTANCE     = 1;
    private static final int MAX_DISTANCE_CEILING     = 2;
    private static final int DEFAULT_MIN_TITLES       = 1;
    private static final int DEFAULT_LIMIT            = 500;
    private static final int MAX_LIMIT                = 5000;

    private final ActressRepository actressRepo;
    private final Jdbi jdbi;

    public FindLev1ActressPairsTool(ActressRepository actressRepo, Jdbi jdbi) {
        this.actressRepo = actressRepo;
        this.jdbi = jdbi;
    }

    @Override public String name() { return "find_lev1_actress_pairs"; }
    @Override public String description() {
        return "Find pairs of actresses whose canonical names are within a small Levenshtein distance (default 1, max 2). "
             + "Catches substitution, insertion, and deletion typos (Hoshisaki/Hoshizaki, Kashi/Kashii, Aisai/Aise). "
             + "Optional first-token bucketing prunes the candidate space; tier-mismatch and min-titles filters surface "
             + "high-impact collisions (e.g. SUPERSTAR/LIBRARY).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("max_distance", "integer",
                        "Maximum Levenshtein distance on full canonical_name. Default 1, max 2.",
                        DEFAULT_MAX_DISTANCE)
                .prop("first_token_must_match", "boolean",
                        "When true, restrict to pairs sharing the first whitespace-delimited token (case-insensitive). "
                        + "Massively prunes the candidate space. Default true.",
                        true)
                .prop("min_titles_either", "integer",
                        "At least one side of the pair must have ≥ this many titles. Default 1 (filters phantom-vs-phantom).",
                        DEFAULT_MIN_TITLES)
                .prop("tier_mismatch_only", "boolean",
                        "When true, only return pairs whose tier differs (high-signal subset). Default false.",
                        false)
                .prop("exclude_sentinel", "boolean",
                        "Exclude sentinel actresses (Various / Unknown / Amateur). Default true.",
                        true)
                .prop("limit", "integer",
                        "Maximum number of pairs to return. Default 500, max 5000.",
                        DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int maxDist            = Math.max(1, Math.min(Schemas.optInt(args, "max_distance", DEFAULT_MAX_DISTANCE), MAX_DISTANCE_CEILING));
        boolean firstTokenOnly = Schemas.optBoolean(args, "first_token_must_match", true);
        int minTitles          = Math.max(0, Schemas.optInt(args, "min_titles_either", DEFAULT_MIN_TITLES));
        boolean tierMismatch   = Schemas.optBoolean(args, "tier_mismatch_only", false);
        boolean excludeSentinel = Schemas.optBoolean(args, "exclude_sentinel", true);
        int limit              = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        Set<Long> sentinelIds = excludeSentinel ? sentinelActressIds() : Set.of();
        Map<Long, Integer> titleCounts = titleCountsByActress();

        // Build the candidate set.
        List<Entry> entries = new ArrayList<>();
        for (Actress a : actressRepo.findAll()) {
            String name = a.getCanonicalName();
            if (name == null || name.isBlank()) continue;
            if (sentinelIds.contains(a.getId())) continue;
            String norm = name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (norm.isEmpty()) continue;
            String firstTok = norm.split(" ", 2)[0];
            int titles = titleCounts.getOrDefault(a.getId(), 0);
            entries.add(new Entry(a.getId(), name, norm, firstTok, a.getTier(), titles));
        }

        // Build buckets if first-token pruning is enabled; otherwise single bucket.
        List<List<Entry>> buckets = new ArrayList<>();
        if (firstTokenOnly) {
            Map<String, List<Entry>> byTok = new HashMap<>();
            for (Entry e : entries) byTok.computeIfAbsent(e.firstTok, k -> new ArrayList<>()).add(e);
            buckets.addAll(byTok.values());
        } else {
            buckets.add(entries);
        }

        List<Pair> pairs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (List<Entry> bucket : buckets) {
            if (bucket.size() < 2) continue;
            // Sort the bucket by normalized length for length-difference prefilter early-exit.
            bucket.sort(Comparator.comparingInt(e -> e.normalized.length()));
            for (int i = 0; i < bucket.size(); i++) {
                Entry a = bucket.get(i);
                for (int j = i + 1; j < bucket.size(); j++) {
                    Entry b = bucket.get(j);
                    if (b.normalized.length() - a.normalized.length() > maxDist) break;
                    if (a.actressId == b.actressId) continue;
                    if (a.normalized.equals(b.normalized)) continue;
                    if (minTitles > 0 && a.titleCount < minTitles && b.titleCount < minTitles) continue;
                    if (tierMismatch && a.tier == b.tier) continue;

                    int d = levenshtein(a.normalized, b.normalized, maxDist);
                    if (d < 0 || d > maxDist) continue;

                    long key = pairKey(a.actressId, b.actressId);
                    if (!seen.add(key)) continue;

                    // Order sides deterministically: lower id first.
                    Entry lo = a.actressId < b.actressId ? a : b;
                    Entry hi = a.actressId < b.actressId ? b : a;
                    pairs.add(new Pair(
                            new Side(lo.actressId, lo.display, lo.tier, lo.titleCount),
                            new Side(hi.actressId, hi.display, hi.tier, hi.titleCount),
                            d));
                }
            }
        }

        // Sort: distance ASC, then max(titlesA, titlesB) DESC (highest-impact first), then names.
        pairs.sort(Comparator
                .comparingInt((Pair p) -> p.distance)
                .thenComparingInt(p -> -Math.max(p.a.titleCount, p.b.titleCount))
                .thenComparing(p -> p.a.name)
                .thenComparing(p -> p.b.name));

        if (pairs.size() > limit) {
            pairs = new ArrayList<>(pairs.subList(0, limit));
        }
        return new Result(pairs.size(), pairs);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Set<Long> sentinelActressIds() {
        if (jdbi == null) return Set.of();
        return jdbi.withHandle(h -> new HashSet<>(
                h.createQuery("SELECT id FROM actresses WHERE is_sentinel = 1")
                 .mapTo(Long.class)
                 .list()));
    }

    private Map<Long, Integer> titleCountsByActress() {
        if (jdbi == null) return Map.of();
        Map<Long, Integer> out = new HashMap<>();
        jdbi.useHandle(h ->
                h.createQuery("SELECT actress_id, COUNT(DISTINCT title_id) AS c FROM title_actresses GROUP BY actress_id")
                 .mapToMap()
                 .forEach(row -> {
                     Number id = (Number) row.get("actress_id");
                     Number c  = (Number) row.get("c");
                     if (id != null && c != null) out.put(id.longValue(), c.intValue());
                 })
        );
        return out;
    }

    private static long pairKey(long a, long b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        return (lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /**
     * Standard iterative two-row Levenshtein with early-exit: returns -1 if the
     * distance certainly exceeds {@code threshold}.
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

    private record Entry(long actressId, String display, String normalized, String firstTok,
                         Actress.Tier tier, int titleCount) {}

    public record Side(long actressId, String name, Actress.Tier tier, int titleCount) {}
    public record Pair(Side a, Side b, int distance) {}
    public record Result(int count, List<Pair> pairs) {}
}
