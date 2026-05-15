package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
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
 * Find pairs of actresses whose canonical names differ only by how a Japanese long vowel
 * was romanized — e.g. {@code Ooba}/{@code Oba}, {@code Saijou}/{@code Saijo},
 * {@code Yuuki}/{@code Yuki}, {@code Tohru}/{@code Toru}.
 *
 * <p>Levenshtein-based {@link FindSimilarActressesTool} misses these systematically:
 * the diff is length-1 on a short token and is masked by the {@code min_length} filter,
 * and a single character class is dwarfed by other letters.
 *
 * <p>Algorithm: lower-case each canonical name, split into whitespace tokens, collapse
 * the long-vowel digraphs ({@code oo→o}, {@code ou→o}, {@code uu→u}, {@code oh→o},
 * {@code aa→a}) within each token, and bucket by the result. Buckets with ≥2 distinct
 * actress ids yield candidate pairs.
 *
 * <p>For each pair we also report the {@code variant_type} (the substitution that
 * produced the diff), the per-actress title count, and the number of co-credited
 * titles (titles where both actresses appear in {@code title_actresses}). Pairs
 * already linked through {@code actress_aliases} are excluded — they're resolved.
 *
 * <p>This tool is read-only; no filesystem access.
 */
public class FindLongVowelActressVariantsTool implements Tool {

    private static final int DEFAULT_MIN_TITLE_COUNT = 1;
    private static final int DEFAULT_MAX_RESULTS     = 100;
    private static final int MAX_RESULTS_CEILING     = 1000;

    /** Ordered: longer/more-specific substitutions first so the diff classifier picks the right one. */
    private static final String[][] SUBSTITUTIONS = {
            { "oo", "o" },
            { "ou", "o" },
            { "uu", "u" },
            { "oh", "o" },
            { "aa", "a" },
    };

    private final ActressRepository actressRepo;
    private final Jdbi jdbi;

    public FindLongVowelActressVariantsTool(ActressRepository actressRepo, Jdbi jdbi) {
        this.actressRepo = actressRepo;
        this.jdbi = jdbi;
    }

    @Override public String name() { return "find_long_vowel_actress_variants"; }
    @Override public String description() {
        return "Find pairs of actresses whose canonical names differ only by how a Japanese long vowel was romanized "
             + "(e.g. Ooba/Oba, Saijou/Saijo, Yuuki/Yuki, Tohru/Toru). Each pair reports the substitution type, per-side "
             + "title counts, and co-credited title count. Pairs already linked through actress_aliases are excluded.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("min_title_count", "integer",
                        "Drop pairs where both sides have fewer than this many titles. Default 1 (filter out 0-title shells).",
                        DEFAULT_MIN_TITLE_COUNT)
                .prop("max_results", "integer",
                        "Maximum number of pairs to return. Default 100, max 1000.",
                        DEFAULT_MAX_RESULTS)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int minTitleCount = Math.max(0, Schemas.optInt(args, "min_title_count", DEFAULT_MIN_TITLE_COUNT));
        int maxResults    = Math.max(1, Math.min(Schemas.optInt(args, "max_results", DEFAULT_MAX_RESULTS), MAX_RESULTS_CEILING));

        // ── Load all actresses + title counts + alias links ─────────────────
        List<Actress> all = actressRepo.findAll();
        Map<Long, Integer> titleCounts = titleCountsByActress();
        Set<Long> linkedAliasPairs = aliasLinkedPairs(all);

        // ── Bucket by collapsed canonical name ──────────────────────────────
        Map<String, List<Entry>> buckets = new HashMap<>();
        for (Actress a : all) {
            String name = a.getCanonicalName();
            if (name == null || name.isBlank()) continue;
            String collapsed = collapseLongVowels(name);
            buckets.computeIfAbsent(collapsed, k -> new ArrayList<>())
                   .add(new Entry(a.getId(), name, titleCounts.getOrDefault(a.getId(), 0)));
        }

        // ── Form pairs from buckets with size ≥ 2 ───────────────────────────
        List<Pair> pairs = new ArrayList<>();
        for (List<Entry> bucket : buckets.values()) {
            if (bucket.size() < 2) continue;
            for (int i = 0; i < bucket.size(); i++) {
                Entry a = bucket.get(i);
                for (int j = i + 1; j < bucket.size(); j++) {
                    Entry b = bucket.get(j);
                    if (a.actressId == b.actressId) continue;
                    if (a.name.equalsIgnoreCase(b.name)) continue;
                    if (linkedAliasPairs.contains(pairKey(a.actressId, b.actressId))) continue;
                    if (a.titleCount < minTitleCount && b.titleCount < minTitleCount) continue;

                    String variantType = classifyVariant(a.name, b.name);
                    if (variantType == null) continue; // e.g. names collapse-equal but no recognized substitution differs

                    long lowId  = Math.min(a.actressId, b.actressId);
                    long highId = Math.max(a.actressId, b.actressId);
                    Entry lo = (a.actressId == lowId) ? a : b;
                    Entry hi = (a.actressId == lowId) ? b : a;

                    int coCredited = coCreditedTitleCount(lowId, highId);
                    pairs.add(new Pair(
                            new Side(lo.actressId, lo.name, lo.titleCount),
                            new Side(hi.actressId, hi.name, hi.titleCount),
                            variantType,
                            coCredited));
                }
            }
        }

        // ── Order by combined title count, desc; truncate ───────────────────
        pairs.sort(Comparator.<Pair>comparingInt(p -> -(p.actressA.titleCount + p.actressB.titleCount))
                              .thenComparing(p -> p.actressA.name)
                              .thenComparing(p -> p.actressB.name));
        if (pairs.size() > maxResults) {
            pairs = new ArrayList<>(pairs.subList(0, maxResults));
        }

        return new Result(pairs.size(), pairs);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Per-token long-vowel collapse: lower-cases the name, splits on whitespace, then
     * applies the substitution table to each token individually so cross-token vowel
     * runs don't merge (e.g. "Oba Ono" stays "oba ono", not "obano").
     */
    static String collapseLongVowels(String name) {
        String[] tokens = name.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) out.append(' ');
            String t = tokens[i];
            for (String[] sub : SUBSTITUTIONS) {
                t = t.replace(sub[0], sub[1]);
            }
            out.append(t);
        }
        return out.toString();
    }

    /**
     * Identify which substitution accounts for the difference between {@code a} and {@code b}.
     * Returns a label like "Oo/O", "Ou/O", "Uu/U", "Oh/O", "Aa/A", or "Mixed" if more than one
     * class differs, or {@code null} if no recognized substitution differs.
     *
     * <p>Comparison is per-token; tokens at matching positions are checked. If the lengths
     * after collapse differ token-count-wise, we fall back to whole-string comparison.
     */
    static String classifyVariant(String a, String b) {
        String[] ta = a.trim().toLowerCase(Locale.ROOT).split("\\s+");
        String[] tb = b.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (ta.length != tb.length) {
            return classifyTokenPair(String.join(" ", ta), String.join(" ", tb));
        }
        String found = null;
        for (int i = 0; i < ta.length; i++) {
            if (ta[i].equals(tb[i])) continue;
            String cls = classifyTokenPair(ta[i], tb[i]);
            if (cls == null) return null;
            if (found == null) found = cls;
            else if (!found.equals(cls)) found = "Mixed";
        }
        return found;
    }

    /**
     * Decide which substitution explains the diff between two lowercase tokens that already
     * agree under {@link #collapseLongVowels}. Strategy: for each substitution, count how
     * many occurrences of the long form differ between the two tokens. The substitution
     * with the largest non-zero diff wins; ties yield "Mixed".
     */
    private static String classifyTokenPair(String a, String b) {
        int bestDiff = 0;
        String best = null;
        boolean tie = false;
        for (String[] sub : SUBSTITUTIONS) {
            int da = countOccurrences(a, sub[0]);
            int db = countOccurrences(b, sub[0]);
            int diff = Math.abs(da - db);
            if (diff == 0) continue;
            if (diff > bestDiff) {
                bestDiff = diff;
                best = labelOf(sub);
                tie = false;
            } else if (diff == bestDiff) {
                tie = true;
            }
        }
        if (best == null) return null;
        return tie ? "Mixed" : best;
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0, idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }

    private static String labelOf(String[] sub) {
        // sub[0] = "oo", sub[1] = "o" → "Oo/O"
        return capitalize(sub[0]) + "/" + capitalize(sub[1]);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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

    private int coCreditedTitleCount(long aId, long bId) {
        if (jdbi == null) return 0;
        return jdbi.withHandle(h ->
                h.createQuery("""
                          SELECT COUNT(DISTINCT ta1.title_id)
                            FROM title_actresses ta1
                            JOIN title_actresses ta2 ON ta1.title_id = ta2.title_id
                           WHERE ta1.actress_id = :a AND ta2.actress_id = :b
                        """)
                 .bind("a", aId)
                 .bind("b", bId)
                 .mapTo(Integer.class)
                 .one()
        );
    }

    /**
     * Pre-compute the set of {@code (lowId, highId)} pairs that are linked through
     * {@code actress_aliases} — i.e. one actress's canonical name appears as an alias of
     * the other. Such pairs are not anomalies and are excluded from output.
     */
    private Set<Long> aliasLinkedPairs(List<Actress> all) {
        Map<String, Long> nameToId = new HashMap<>();
        for (Actress a : all) {
            if (a.getCanonicalName() != null) {
                nameToId.put(a.getCanonicalName().toLowerCase(Locale.ROOT), a.getId());
            }
        }
        Set<Long> linked = new HashSet<>();
        for (Actress a : all) {
            for (ActressAlias alias : actressRepo.findAliases(a.getId())) {
                String key = alias.aliasName() == null ? null : alias.aliasName().toLowerCase(Locale.ROOT);
                if (key == null) continue;
                Long other = nameToId.get(key);
                if (other != null && other != a.getId()) {
                    linked.add(pairKey(a.getId(), other));
                }
            }
        }
        return linked;
    }

    /** Compose a stable long key from an unordered pair of actress ids. */
    private static long pairKey(long a, long b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        return (lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private record Entry(long actressId, String name, int titleCount) {}

    public record Side(long actressId, String name, int titleCount) {}
    public record Pair(Side actressA, Side actressB, String variantType, int coCreditedTitles) {}
    public record Result(int count, List<Pair> pairs) {}
}
