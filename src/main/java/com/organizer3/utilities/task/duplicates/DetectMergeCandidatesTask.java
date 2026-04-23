package com.organizer3.utilities.task.duplicates;

import com.organizer3.repository.MergeCandidateRepository;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans the titles table for cross-row duplicate candidates and populates
 * {@code merge_candidates}.
 *
 * <p>Detection tiers (in order of confidence):
 * <ol>
 *   <li><b>code-normalization</b> — titles sharing the same {@code base_code} where neither
 *       code has a variant suffix (e.g., {@code ONED-01} vs {@code ONED-001}).</li>
 *   <li><b>variant-suffix</b> — titles sharing the same {@code base_code} where at least one
 *       code carries a variant suffix (e.g., {@code ABP-123} vs {@code ABP-123_U}). These
 *       may be legitimate variants rather than accidental duplicates; user review required.</li>
 * </ol>
 *
 * <p>The scan clears all undecided candidates before inserting fresh ones, so re-running
 * after new syncs is safe. Decided (MERGE / DISMISS) candidates are never overwritten.
 */
@Slf4j
@RequiredArgsConstructor
public final class DetectMergeCandidatesTask implements Task {

    public static final String ID = "duplicates.detect_merge_candidates";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Detect merge candidates",
            "Scan titles for cross-row duplicates (code normalization drift, variant suffixes) " +
                    "and populate the merge-candidates queue.",
            List.of()
    );

    private final MergeCandidateRepository repo;
    private final Jdbi jdbi;
    private final Clock clock;

    public DetectMergeCandidatesTask(MergeCandidateRepository repo, Jdbi jdbi) {
        this(repo, jdbi, Clock.systemUTC());
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        // ── Scan phase ─────────────────────────────────────────────────────────
        io.phaseStart("scan", "Scan for merge candidates");

        record TitleRow(String code, String baseCode) {}

        // Load all title groups where base_code is shared by more than one code
        List<List<TitleRow>> groups = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT code, base_code
                        FROM titles
                        WHERE base_code IS NOT NULL
                          AND base_code IN (
                              SELECT base_code FROM titles
                              WHERE base_code IS NOT NULL
                              GROUP BY base_code HAVING COUNT(*) > 1
                          )
                        ORDER BY base_code, code
                        """)
                        .map((rs, ctx) -> new TitleRow(rs.getString("code"), rs.getString("base_code")))
                        .list()
        ).stream()
                .collect(java.util.stream.Collectors.groupingBy(TitleRow::baseCode))
                .values()
                .stream()
                .<List<TitleRow>>map(ArrayList::new)
                .toList();

        int groupCount = groups.size();
        int totalPairs = groups.stream().mapToInt(g -> pairs(g.size())).sum();
        io.phaseLog("scan", groupCount + " base_code group(s) with shared codes → " + totalPairs + " candidate pair(s)");
        io.phaseEnd("scan", "ok", groupCount + " group(s), " + totalPairs + " pair(s)");

        if (totalPairs == 0) {
            return;
        }

        // ── Populate phase ─────────────────────────────────────────────────────
        io.phaseStart("populate", "Populate merge_candidates table");
        repo.deleteUndecided();

        String now = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
        int normCount = 0;
        int suffixCount = 0;

        for (List<TitleRow> group : groups) {
            // Generate all pairs within the group
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    TitleRow a = group.get(i);
                    TitleRow b = group.get(j);
                    String confidence = hasSuffix(a.code()) || hasSuffix(b.code())
                            ? "variant-suffix"
                            : "code-normalization";
                    repo.insertIfAbsent(a.code(), b.code(), confidence, now);
                    if ("code-normalization".equals(confidence)) normCount++;
                    else suffixCount++;
                }
            }
        }

        log.info("DetectMergeCandidatesTask: {} code-normalization + {} variant-suffix candidates",
                normCount, suffixCount);
        String summary = normCount + " code-normalization"
                + (suffixCount > 0 ? " · " + suffixCount + " variant-suffix" : "");
        io.phaseEnd("populate", "ok", summary);
    }

    /** Number of unique pairs in a group of size n: n*(n-1)/2. */
    private static int pairs(int n) { return n * (n - 1) / 2; }

    /**
     * Returns true if the code contains a variant suffix — an underscore segment
     * after the numeric part, e.g., {@code ABP-123_U} or {@code PRED-456_4K}.
     */
    static boolean hasSuffix(String code) {
        int dash = code.indexOf('-');
        if (dash < 0) return false;
        String afterDash = code.substring(dash + 1);
        return afterDash.contains("_");
    }
}
