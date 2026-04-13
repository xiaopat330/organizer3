package com.organizer3.command;

import com.organizer3.model.Actress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure detection logic for actress folder name anomalies found during a filesystem scan.
 *
 * <p>Classifies folder names that do not exactly match any known DB actress into three categories:
 * <ol>
 *   <li><b>Swaps</b> — two-word name whose words are reversed matches a DB actress
 *       (e.g. "Shiina Yuna" folder when DB has "Yuna Shiina").</li>
 *   <li><b>Typos</b> — normalized edit distance ≤ {@code TYPO_MAX_DIST} from a DB actress.
 *       Dist=0 typos are romanization variants ("Ryou Tanaka" vs DB "Ryo Tanaka").</li>
 *   <li><b>Unknowns</b> — no close DB match; may be a valid actress not yet imported,
 *       or a folder name too different to auto-match.</li>
 * </ol>
 *
 * <p>Folder names that match any DB actress exactly (case-insensitively) are silently skipped.
 * Swap detection runs before typo detection so that word-order errors are not also reported
 * as typos.
 */
public class ErrorScanService {

    /** Maximum normalized edit distance to classify as a typo. */
    static final int TYPO_MAX_DIST = 1;

    public record SwapResult(String folderName, Actress dbMatch) {}
    public record TypoResult(String folderName, Actress dbMatch, int dist) {}
    public record UnknownResult(String folderName) {}

    public record ScanResults(
            List<SwapResult> swaps,
            List<TypoResult> typos,
            List<UnknownResult> unknowns,
            int totalScanned
    ) {
        public boolean hasIssues() {
            return !swaps.isEmpty() || !typos.isEmpty() || !unknowns.isEmpty();
        }
    }

    /**
     * Classify each folder name against the list of known DB actresses.
     * Folder names that match exactly (case-insensitive) are skipped.
     *
     * @param folderNames unique actress folder names found on the filesystem
     * @param dbActresses all canonical actresses from the DB
     */
    public ScanResults scan(List<String> folderNames, List<Actress> dbActresses) {
        Map<String, Actress> byExact = new HashMap<>();
        for (Actress a : dbActresses) {
            byExact.put(a.getCanonicalName().toLowerCase(), a);
        }

        List<SwapResult> swaps = new ArrayList<>();
        List<TypoResult> typos = new ArrayList<>();
        List<UnknownResult> unknowns = new ArrayList<>();

        for (String folder : folderNames) {
            // 1. Exact match (case-insensitive) → skip
            if (byExact.containsKey(folder.toLowerCase())) continue;

            // 2. Swap: reverse word order and look for an exact DB match
            String swapped = swapWords(folder);
            if (swapped != null && byExact.containsKey(swapped.toLowerCase())) {
                swaps.add(new SwapResult(folder, byExact.get(swapped.toLowerCase())));
                continue;
            }

            // 3. Typo: find the closest DB actress by normalized edit distance
            String normFolder = ActressNameCheckService.normalize(folder);
            int bestDist = Integer.MAX_VALUE;
            Actress bestMatch = null;
            for (Actress a : dbActresses) {
                int d = ActressNameCheckService.levenshtein(
                        normFolder, ActressNameCheckService.normalize(a.getCanonicalName()));
                if (d < bestDist) {
                    bestDist = d;
                    bestMatch = a;
                }
            }

            if (bestDist <= TYPO_MAX_DIST) {
                typos.add(new TypoResult(folder, bestMatch, bestDist));
            } else {
                unknowns.add(new UnknownResult(folder));
            }
        }

        return new ScanResults(swaps, typos, unknowns, folderNames.size());
    }

    /** Returns the two words of {@code name} reversed, or null if it isn't exactly two words. */
    private static String swapWords(String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length != 2) return null;
        return parts[1] + " " + parts[0];
    }
}
