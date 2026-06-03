package com.organizer3.javdb.draft;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates cast-slot resolutions with a count-independent rule.
 *
 * <p>A valid cast must satisfy exactly one of:
 * <ul>
 *   <li><b>Path A</b>: at least 1 real actress (pick or create_new), any number of skips,
 *       and zero sentinels.</li>
 *   <li><b>Path B</b>: exactly 1 sentinel, zero real actresses, zero skips.</li>
 * </ul>
 *
 * <p>An empty resolutions list therefore yields {@code CAST_MODE_VIOLATION}: a draft
 * must have at least one real actress or exactly one placeholder sentinel.
 *
 * <p>Additionally, {@code unresolved} (and any unknown) slots always produce
 * {@code UNRESOLVED_CAST_SLOT}.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §5.1 and §5.2.
 */
public class CastValidator {

    /**
     * Validates the given resolutions against the cast-mode rules.
     *
     * @param resolutions  all current cast-slot resolution strings for this draft.
     *                     Each must be one of: {@code pick}, {@code create_new},
     *                     {@code skip}, {@code sentinel:<id>}, {@code unresolved}.
     * @return a list of error codes; empty means valid.
     */
    public List<String> validate(List<String> resolutions) {
        List<String> errors = new ArrayList<>();

        int pickCount       = 0;
        int createCount     = 0;
        int skipCount       = 0;
        int sentinelCount   = 0;
        int unresolvedCount = 0;

        for (String r : resolutions) {
            if ("pick".equals(r))            pickCount++;
            else if ("create_new".equals(r)) createCount++;
            else if ("skip".equals(r))       skipCount++;
            else if (r != null && r.startsWith("sentinel:")) sentinelCount++;
            else if ("unresolved".equals(r)) unresolvedCount++;
            else unresolvedCount++;  // unknown values treated as unresolved
        }

        if (unresolvedCount > 0) {
            errors.add("UNRESOLVED_CAST_SLOT");
        }

        int realCount = pickCount + createCount;

        // Path A: ≥1 real actress, optional skips, no sentinel.
        // Path B: exactly 1 sentinel, no real actresses, no skips.
        boolean pathA = realCount >= 1 && sentinelCount == 0;
        boolean pathB = sentinelCount == 1 && realCount == 0 && skipCount == 0;

        if (!pathA && !pathB) {
            errors.add("CAST_MODE_VIOLATION");
        }

        return errors.stream().distinct().toList();
    }
}
