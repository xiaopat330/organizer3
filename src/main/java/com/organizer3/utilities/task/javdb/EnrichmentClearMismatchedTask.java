package com.organizer3.utilities.task.javdb;

import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clears javdb enrichment rows whose chosen slug doesn't match the linked actress's
 * filmography (Step 5 of {@code spec/PROPOSAL_JAVDB_SLUG_VERIFICATION.md}).
 *
 * <p>Two sources of cleanup candidates:
 * <ol>
 *   <li><b>Authoritative</b>: every actress with a known {@code javdb_actress_staging.javdb_slug}
 *       has her filmography fetched once. Any of her enriched titles whose stored slug differs
 *       from {@code filmography[code]} (or whose code isn't in her filmography at all) is a
 *       cleanup target.</li>
 *   <li><b>Heuristic backstop</b>: titles flagged by the cast-mismatch detection query
 *       (see {@link com.organizer3.mcp.tools.FindEnrichmentCastMismatchesTool}) but whose
 *       linked actresses have no filmography available — we can't authoritatively re-resolve
 *       these, but we know the current row is wrong, so we still clear it.</li>
 * </ol>
 *
 * <p>For each cleanup target, the task:
 * <ul>
 *   <li>Deletes the {@code title_javdb_enrichment} row (cascades to {@code title_enrichment_tags}).</li>
 *   <li>NULLs {@code title_original}, {@code title_english}, {@code release_date}, {@code notes}.</li>
 *   <li>NULLs {@code grade}/{@code grade_source} <i>only when {@code grade_source = 'enrichment'}</i> —
 *       manual and AI grades are preserved.</li>
 *   <li>Force-enqueues a {@code fetch_title} job so the runner re-resolves via the new
 *       actress-anchored path.</li>
 * </ul>
 *
 * <p>Honors {@code dryRun} — when true, no DB writes; the summary lists what would happen.
 * Cancellation: polled between actresses and between cleanup writes.
 */
@Slf4j
public final class EnrichmentClearMismatchedTask implements Task {

    public static final String ID = "enrichment.clear_mismatched";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Clear mismatched javdb enrichments",
            "Re-validate enriched titles against each actress's javdb filmography. Clears wrong-slug "
                    + "rows + their grades (only when grade_source='enrichment') and re-enqueues the "
                    + "title for actress-anchored re-fetch. Use dryRun=true to preview scope.",
            List.of(new TaskSpec.InputSpec(
                    "dryRun", "Preview only — when 'true', no DB writes",
                    TaskSpec.InputSpec.InputType.STRING, false))
    );

    private final Jdbi jdbi;
    private final JavdbSlugResolver slugResolver;
    private final EnrichmentQueue queue;

    public EnrichmentClearMismatchedTask(Jdbi jdbi,
                                         JavdbSlugResolver slugResolver,
                                         EnrichmentQueue queue) {
        this.jdbi = jdbi;
        this.slugResolver = slugResolver;
        this.queue = queue;
    }

    @Override public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        boolean dryRun = inputs.values().containsKey("dryRun")
                && "true".equalsIgnoreCase(inputs.getString("dryRun"));

        // ── Plan: gather actresses whose filmography we'll fetch ───────────────
        io.phaseStart("plan", "Loading actresses with enriched titles");
        List<ActressTarget> actresses = loadActressesWithEnrichments();
        io.phaseEnd("plan", "ok",
                actresses.size() + " actress(es) with enriched titles + javdb slug");

        // ── Validate: fetch each filmography, walk her titles ──────────────────
        io.phaseStart("validate", "Fetching filmographies + validating slugs");
        Map<Long, CleanupTarget> targets = new LinkedHashMap<>();
        Set<Long> confirmedCorrect = new LinkedHashSet<>();
        int fetched = 0;
        for (int i = 0; i < actresses.size(); i++) {
            if (io.isCancellationRequested()) {
                io.phaseLog("validate", "Cancelled after " + i + " actress(es)");
                break;
            }
            ActressTarget at = actresses.get(i);
            io.phaseProgress("validate", i, actresses.size(),
                    at.canonicalName + " (" + at.javdbSlug + ")");
            Map<String, String> filmography;
            try {
                filmography = slugResolver.filmographyOf(at.javdbSlug);
                fetched++;
            } catch (RuntimeException e) {
                io.phaseLog("validate",
                        "FAIL filmography fetch for " + at.canonicalName + ": " + e.getMessage());
                continue;
            }

            for (EnrichedTitle et : at.titles) {
                String correctSlug = filmography.get(et.code);
                if (correctSlug != null && correctSlug.equals(et.currentSlug)) {
                    confirmedCorrect.add(et.titleId);
                    continue;
                }
                String reason = correctSlug == null
                        ? "code not in filmography"
                        : "wrong slug: stored=" + et.currentSlug + " filmography=" + correctSlug;
                targets.computeIfAbsent(et.titleId,
                                tid -> new CleanupTarget(tid, et.code, at.actressId, reason))
                        .actressIds.add(at.actressId);
            }
        }
        // A title may have been marked as a target via one actress but confirmed correct via
        // another (collab title): exclude any titleId that any other actress confirmed.
        targets.keySet().removeAll(confirmedCorrect);
        io.phaseEnd("validate", "ok",
                fetched + " filmography fetch(es) · " + targets.size() + " mismatch(es) found");

        // ── Backstop: heuristic-detected mismatches we couldn't validate ───────
        io.phaseStart("backstop", "Adding heuristic-detected mismatches without filmography coverage");
        List<HeuristicTarget> heuristic = loadHeuristicMismatches();
        int added = 0;
        for (HeuristicTarget ht : heuristic) {
            if (confirmedCorrect.contains(ht.titleId)) continue;
            if (targets.containsKey(ht.titleId)) continue;
            targets.put(ht.titleId, new CleanupTarget(
                    ht.titleId, ht.code, ht.actressId, "heuristic cast-mismatch"));
            targets.get(ht.titleId).actressIds.add(ht.actressId);
            added++;
        }
        io.phaseEnd("backstop", "ok", added + " heuristic mismatch(es) added");

        // ── Execute ────────────────────────────────────────────────────────────
        if (dryRun) {
            io.phaseStart("execute", "Dry run — no writes");
            int gradesCleared = countGradesThatWouldClear(targets.keySet());
            io.phaseEnd("execute", "ok",
                    "dryRun: would clear " + targets.size() + " enrichment(s), "
                            + gradesCleared + " enrichment-source grade(s)");
            return;
        }

        io.phaseStart("execute", "Clearing rows + re-enqueueing");
        int cleared = 0;
        int gradesCleared = 0;
        int reenqueued = 0;
        int n = 0;
        for (CleanupTarget t : targets.values()) {
            if (io.isCancellationRequested()) {
                io.phaseLog("execute", "Cancelled after " + n + " of " + targets.size());
                break;
            }
            try {
                int gradeNulled = clearOne(t.titleId);
                cleared++;
                gradesCleared += gradeNulled;
                long actressForReenqueue = t.actressIds.iterator().next();
                queue.enqueueTitleForce(t.titleId, actressForReenqueue);
                reenqueued++;
            } catch (RuntimeException e) {
                log.warn("EnrichmentClearMismatchedTask: failed for titleId={}: {}",
                        t.titleId, e.getMessage());
                io.phaseLog("execute", "FAIL titleId=" + t.titleId + " — " + e.getMessage());
            }
            n++;
        }
        io.phaseEnd("execute", "ok",
                cleared + " cleared · " + gradesCleared + " grade(s) reset · "
                        + reenqueued + " re-enqueued");
    }

    /** Load every actress with a javdb_slug who has at least one enriched title. */
    private List<ActressTarget> loadActressesWithEnrichments() {
        Map<Long, ActressTarget> byId = new LinkedHashMap<>();
        jdbi.useHandle(h -> h.createQuery("""
                SELECT a.id            AS actress_id,
                       a.canonical_name AS canonical_name,
                       jas.javdb_slug  AS javdb_slug,
                       t.id            AS title_id,
                       t.code          AS code,
                       e.javdb_slug    AS title_slug
                FROM actresses a
                JOIN javdb_actress_staging jas ON jas.actress_id = a.id
                JOIN title_actresses ta ON ta.actress_id = a.id
                JOIN titles t ON t.id = ta.title_id
                JOIN title_javdb_enrichment e ON e.title_id = t.id
                WHERE COALESCE(a.is_sentinel, 0) = 0
                  AND jas.javdb_slug IS NOT NULL
                  AND jas.javdb_slug != ''
                ORDER BY a.canonical_name, t.code
                """)
                .map((rs, ctx) -> new Object[]{
                        rs.getLong("actress_id"),
                        rs.getString("canonical_name"),
                        rs.getString("javdb_slug"),
                        rs.getLong("title_id"),
                        rs.getString("code"),
                        rs.getString("title_slug")
                })
                .forEach(row -> {
                    long actressId = (Long) row[0];
                    ActressTarget at = byId.computeIfAbsent(actressId,
                            id -> new ActressTarget(id, (String) row[1], (String) row[2]));
                    at.titles.add(new EnrichedTitle((Long) row[3], (String) row[4], (String) row[5]));
                }));
        return new ArrayList<>(byId.values());
    }

    /**
     * Fallback list: titles flagged by the cast heuristic that we cannot authoritatively re-resolve
     * because none of their linked actresses has a known javdb slug. Mirrors the WHERE clause of
     * {@link com.organizer3.mcp.tools.FindEnrichmentCastMismatchesTool}.
     */
    private List<HeuristicTarget> loadHeuristicMismatches() {
        List<HeuristicTarget> out = new ArrayList<>();
        jdbi.useHandle(h -> h.createQuery("""
                SELECT t.id AS title_id, t.code AS code, a.id AS actress_id
                FROM titles t
                JOIN title_actresses ta ON ta.title_id = t.id
                JOIN actresses a        ON a.id = ta.actress_id
                JOIN title_javdb_enrichment e ON e.title_id = t.id
                WHERE COALESCE(a.is_sentinel, 0) = 0
                  AND a.stage_name IS NOT NULL
                  AND e.cast_json IS NOT NULL
                  AND REPLACE(e.cast_json, ' ', '') NOT LIKE '%' || REPLACE(a.stage_name, ' ', '') || '%'
                  AND (
                    a.alternate_names_json IS NULL
                    OR NOT EXISTS (
                      SELECT 1 FROM json_each(a.alternate_names_json) alt
                      WHERE json_extract(alt.value, '$.name') IS NOT NULL
                        AND REPLACE(e.cast_json, ' ', '')
                            LIKE '%' || REPLACE(json_extract(alt.value, '$.name'), ' ', '') || '%'
                    )
                  )
                """)
                .map((rs, ctx) -> new HeuristicTarget(
                        rs.getLong("title_id"), rs.getString("code"), rs.getLong("actress_id")))
                .forEach(out::add));
        return out;
    }

    /**
     * Clears one title's enrichment + cached fields. Returns 1 if the grade was reset
     * (was {@code grade_source='enrichment'}), 0 otherwise.
     */
    private int clearOne(long titleId) {
        return jdbi.inTransaction(h -> {
            h.createUpdate("DELETE FROM title_javdb_enrichment WHERE title_id = :id")
                    .bind("id", titleId).execute();
            h.createUpdate("""
                    UPDATE titles SET title_original = NULL, title_english = NULL,
                                      release_date = NULL, notes = NULL
                    WHERE id = :id
                    """).bind("id", titleId).execute();
            int gradeNulled = h.createUpdate("""
                    UPDATE titles SET grade = NULL, grade_source = NULL
                    WHERE id = :id AND grade_source = 'enrichment'
                    """).bind("id", titleId).execute();
            // title_enrichment_tags cascades via FK.
            return gradeNulled;
        });
    }

    private int countGradesThatWouldClear(Set<Long> titleIds) {
        if (titleIds.isEmpty()) return 0;
        return jdbi.withHandle(h -> {
            int n = 0;
            for (Long id : titleIds) {
                n += h.createQuery(
                        "SELECT COUNT(*) FROM titles WHERE id = :id AND grade_source = 'enrichment'")
                        .bind("id", id).mapTo(Integer.class).one();
            }
            return n;
        });
    }

    // ── value types ────────────────────────────────────────────────────────────

    private static final class ActressTarget {
        final long actressId;
        final String canonicalName;
        final String javdbSlug;
        final List<EnrichedTitle> titles = new ArrayList<>();
        ActressTarget(long actressId, String canonicalName, String javdbSlug) {
            this.actressId = actressId;
            this.canonicalName = canonicalName;
            this.javdbSlug = javdbSlug;
        }
    }

    private record EnrichedTitle(long titleId, String code, String currentSlug) {}

    private record HeuristicTarget(long titleId, String code, long actressId) {}

    private static final class CleanupTarget {
        final long titleId;
        final String code;
        @SuppressWarnings("unused") final long firstActressId;
        final String reason;
        final Set<Long> actressIds = new LinkedHashSet<>();
        CleanupTarget(long titleId, String code, long firstActressId, String reason) {
            this.titleId = titleId;
            this.code = code;
            this.firstActressId = firstActressId;
            this.reason = reason;
        }
    }
}
