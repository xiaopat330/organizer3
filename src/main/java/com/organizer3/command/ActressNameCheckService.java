package com.organizer3.command;

import com.organizer3.model.Actress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure detection logic for actress name anomalies.
 *
 * <p>Two checks:
 * <ol>
 *   <li><b>Swaps</b> — the same two-word name entered with word order reversed
 *       ("Given Family" vs "Family Given").</li>
 *   <li><b>Typos</b> — two actresses who share a surname but have similar given names,
 *       or share a given name but have similar surnames, where one side has ≤ the
 *       {@code suspectThreshold} number of titles (suggesting a stray misspelling entry).</li>
 * </ol>
 *
 * <p>Romanization normalization collapses common long-vowel variants before comparison
 * so that "Ryou/Ryo", "Yuuki/Yuki", etc. score as dist=0.
 */
public class ActressNameCheckService {

    /** An actress with ≤ this many titles on the "suspect" side of a typo pair. */
    static final int SUSPECT_THRESHOLD = 3;

    // ── Romanization normalization ──────────────────────────────────────────

    private static final String[][] ROMAN_SUBS = {
            {"ou", "o"}, {"oo", "o"}, {"oh", "o"},
            {"uu", "u"}, {"ii", "i"}, {"aa", "a"},
            {"tsu", "tu"}, {"shi", "si"}, {"chi", "ci"},
    };

    static String normalize(String s) {
        s = s.toLowerCase().strip();
        for (String[] sub : ROMAN_SUBS) {
            s = s.replace(sub[0], sub[1]);
        }
        return s;
    }

    static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.length() > b.length()) { String t = a; a = b; b = t; }
        int[] prev = new int[b.length() + 1];
        for (int i = 0; i <= b.length(); i++) prev[i] = i;
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                curr[j] = Math.min(
                        Math.min(prev[j] + 1, curr[j - 1] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = curr;
        }
        return prev[b.length()];
    }

    static int ndist(String a, String b) {
        return levenshtein(normalize(a), normalize(b));
    }

    // ── Result records ──────────────────────────────────────────────────────

    /**
     * A pair of actresses whose canonical names are each other's word-order reversal.
     * The entry with more titles is considered canonical.
     */
    public record SwapPair(
            Actress canonical, int canonicalCount,
            Actress suspect,   int suspectCount
    ) {}

    /**
     * A pair of actresses with similar names (dist=1 after normalization) where one has
     * suspiciously few titles.
     */
    public record TypoPair(
            int dist,
            Actress canonical, int canonicalCount,
            Actress suspect,   int suspectCount,
            /** True = same surname / similar given name.  False = same given name / similar surname. */
            boolean sameSurname
    ) {}

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Find all name-order swaps among the given actresses.
     *
     * @param names       all actresses
     * @param titleCounts combined title counts (from both FK and junction table)
     */
    public List<SwapPair> findSwaps(List<Actress> names, Map<Long, Integer> titleCounts) {
        Map<String, Actress> byLower = new HashMap<>();
        for (Actress a : names) {
            byLower.put(a.getCanonicalName().toLowerCase(), a);
        }

        List<SwapPair> results = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (Actress a : names) {
            String[] parts = a.getCanonicalName().strip().split("\\s+");
            if (parts.length != 2) continue;
            String swappedKey = (parts[1] + " " + parts[0]).toLowerCase();
            if (swappedKey.equals(a.getCanonicalName().toLowerCase())) continue;

            Actress b = byLower.get(swappedKey);
            if (b == null) continue;

            long minId = Math.min(a.getId(), b.getId());
            long maxId = Math.max(a.getId(), b.getId());
            long pairKey = minId * 10_000_000L + maxId;
            if (seen.contains(pairKey)) continue;
            seen.add(pairKey);

            int ca = titleCounts.getOrDefault(a.getId(), 0);
            int cb = titleCounts.getOrDefault(b.getId(), 0);
            Actress canonical = ca >= cb ? a : b;
            Actress suspect   = ca >= cb ? b : a;
            results.add(new SwapPair(canonical, Math.max(ca, cb), suspect, Math.min(ca, cb)));
        }
        return results;
    }

    /**
     * Find likely typo pairs (dist=1 after normalization) where one side has ≤ {@value SUSPECT_THRESHOLD} titles.
     * Returns both same-surname and same-given-name pairs, sorted by suspect name.
     *
     * @param names       all actresses
     * @param titleCounts combined title counts (from both FK and junction table)
     */
    public List<TypoPair> findTypos(List<Actress> names, Map<Long, Integer> titleCounts) {
        List<TypoPair> results = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        findSurnamePairs(names, titleCounts, seen, results);
        findGivenPairs(names, titleCounts, seen, results);
        results.sort((a, b) -> a.suspect().getCanonicalName().compareToIgnoreCase(b.suspect().getCanonicalName()));
        return results;
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /** Same surname, dist=1 given name, one side ≤ SUSPECT_THRESHOLD titles. */
    private void findSurnamePairs(List<Actress> names, Map<Long, Integer> titleCounts,
                                  Set<Long> seen, List<TypoPair> results) {
        Map<String, List<Actress>> bySurname = new HashMap<>();
        for (Actress a : names) {
            String[] parts = splitName(a.getCanonicalName());
            if (parts[1].isEmpty()) continue;
            bySurname.computeIfAbsent(parts[1].toLowerCase(), k -> new ArrayList<>()).add(a);
        }

        for (List<Actress> group : bySurname.values()) {
            if (group.size() < 2) continue;
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Actress a = group.get(i);
                    Actress b = group.get(j);
                    int ta = titleCounts.getOrDefault(a.getId(), 0);
                    int tb = titleCounts.getOrDefault(b.getId(), 0);
                    if (!isOneSuspect(ta, tb)) continue;

                    String givenA = splitName(a.getCanonicalName())[0];
                    String givenB = splitName(b.getCanonicalName())[0];
                    if (Math.abs(givenA.length() - givenB.length()) > 2) continue;

                    int d = ndist(givenA, givenB);
                    if (d <= 0 || d > 1) continue;

                    addPair(seen, results, d, a, ta, b, tb, true);
                }
            }
        }
    }

    /** Same given name, dist=1 surname, one side ≤ SUSPECT_THRESHOLD titles. */
    private void findGivenPairs(List<Actress> names, Map<Long, Integer> titleCounts,
                                Set<Long> seen, List<TypoPair> results) {
        Map<String, List<Actress>> byGiven = new HashMap<>();
        for (Actress a : names) {
            String[] parts = splitName(a.getCanonicalName());
            if (parts[1].isEmpty()) continue;
            byGiven.computeIfAbsent(parts[0].toLowerCase(), k -> new ArrayList<>()).add(a);
        }

        for (List<Actress> group : byGiven.values()) {
            if (group.size() < 2) continue;
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Actress a = group.get(i);
                    Actress b = group.get(j);
                    int ta = titleCounts.getOrDefault(a.getId(), 0);
                    int tb = titleCounts.getOrDefault(b.getId(), 0);
                    if (!isOneSuspect(ta, tb)) continue;

                    String famA = splitName(a.getCanonicalName())[1];
                    String famB = splitName(b.getCanonicalName())[1];
                    if (Math.abs(famA.length() - famB.length()) > 2) continue;

                    int d = ndist(famA, famB);
                    if (d <= 0 || d > 1) continue;

                    addPair(seen, results, d, a, ta, b, tb, false);
                }
            }
        }
    }

    private boolean isOneSuspect(int ta, int tb) {
        return (ta <= SUSPECT_THRESHOLD && tb > SUSPECT_THRESHOLD)
                || (tb <= SUSPECT_THRESHOLD && ta > SUSPECT_THRESHOLD);
    }

    private void addPair(Set<Long> seen, List<TypoPair> results,
                         int d, Actress a, int ta, Actress b, int tb, boolean sameSurname) {
        long minId = Math.min(a.getId(), b.getId());
        long maxId = Math.max(a.getId(), b.getId());
        long pairKey = minId * 10_000_000L + maxId;
        if (seen.contains(pairKey)) return;
        seen.add(pairKey);

        Actress canonical = ta >= tb ? a : b;
        Actress suspect   = ta >= tb ? b : a;
        int cc = Math.max(ta, tb);
        int sc = Math.min(ta, tb);
        results.add(new TypoPair(d, canonical, cc, suspect, sc, sameSurname));
    }

    /** Split "Given Family..." → [given, family]. Returns ["name",""] for single-word names. */
    static String[] splitName(String name) {
        String[] parts = name.strip().split("\\s+", 2);
        return parts.length == 2 ? parts : new String[]{parts[0], ""};
    }
}
