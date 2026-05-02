package com.organizer3.javdb.draft;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates cast-slot resolutions against the three cast modes defined in §5.1.
 *
 * <p>The <em>mode</em> is determined by the number of stage_names javdb returned for
 * the title (the original javdb cast count), not by the number of resolution rows.
 * The caller must supply {@code javdbStageNameCount} explicitly so the validator
 * can apply the correct mode rule.
 *
 * <h3>Mode rules (by javdb stage_name count)</h3>
 * <table border="1">
 *   <tr><th>stage_names</th><th>mode</th><th>rule</th></tr>
 *   <tr><td>0</td><td>sentinel-only</td>
 *       <td>Exactly 1 sentinel resolution. No pick/create_new/skip.</td></tr>
 *   <tr><td>1</td><td>strict</td>
 *       <td>≥1 real actress (pick or create_new). Sentinel forbidden. SKIP forbidden.</td></tr>
 *   <tr><td>≥2</td><td>multi-actress (relaxed)</td>
 *       <td>Path A: ≥1 real actress with optional SKIPs; OR Path B: exactly 1 sentinel,
 *           no real actresses, no skips. No mixing.</td></tr>
 * </table>
 *
 * <p>Additionally, {@code unresolved} slots always produce an error.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §5.1 and §5.2.
 */
public class CastValidator {

    /**
     * Validates the given resolutions against the cast-mode rules.
     *
     * @param javdbStageNameCount  the number of cast entries javdb returned for this title.
     *                             Determines which mode rule applies.
     * @param resolutions          all current cast-slot resolution strings for this draft.
     *                             Each must be one of: {@code pick}, {@code create_new},
     *                             {@code skip}, {@code sentinel:<id>}, {@code unresolved}.
     * @return a list of error codes; empty means valid.
     */
    public List<String> validate(int javdbStageNameCount, List<String> resolutions) {
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

        if (javdbStageNameCount == 0) {
            // Mode: sentinel-only (0 javdb stage_names)
            if (sentinelCount != 1 || realCount > 0 || skipCount > 0) {
                errors.add("CAST_MODE_VIOLATION");
            }
        } else if (javdbStageNameCount == 1) {
            // Mode: strict (1 javdb stage_name)
            if (sentinelCount > 0 || skipCount > 0 || realCount < 1) {
                errors.add("CAST_MODE_VIOLATION");
            }
        } else {
            // Mode: multi-actress / relaxed (≥2 javdb stage_names)
            // Path A: ≥1 real actress, optional skips, no sentinel
            // Path B: exactly 1 sentinel, no real actresses, no skips
            boolean pathA = realCount >= 1 && sentinelCount == 0;
            boolean pathB = sentinelCount == 1 && realCount == 0 && skipCount == 0;
            if (!pathA && !pathB) {
                errors.add("CAST_MODE_VIOLATION");
            }
        }

        return errors.stream().distinct().toList();
    }
}
