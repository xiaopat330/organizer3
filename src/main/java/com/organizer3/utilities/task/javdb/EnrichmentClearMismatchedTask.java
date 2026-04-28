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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Clears javdb enrichment rows whose chosen slug doesn't match the linked actress's
 * filmography (Step 5 of {@code spec/PROPOSAL_JAVDB_SLUG_VERIFICATION.md}).
 *
 * <p><b>Resumability:</b> processes one actress at a time. Each title's clear + re-enqueue
 * commits in its own transaction <i>before</i> moving on. If the JVM dies (laptop crash,
 * battery, etc.) mid-run, every cleanup that already committed survives, and a re-run
 * resumes naturally — cleared titles are no longer in the enriched-titles list, so they
 * just don't appear as candidates the second time around.
 *
 * <p><b>Collab handling:</b> a title with multiple slug-bearing actresses is only evaluated
 * once <i>all</i> of those actresses' filmographies have been fetched. If <i>any</i> of them
 * confirms the stored slug (her filmography contains {@code code → storedSlug}), the title
 * is left intact — it's correctly attributed to that collab partner.
 *
 * <p><b>Heuristic backstop:</b> after the streaming pass, a final SQL query catches any
 * still-enriched titles whose linked actresses have no javdb slug at all (so they were never
 * filmography-validated) and which the cast-mismatch heuristic flagged. Those rows are
 * cleared as well — bad enrichment beats missing enrichment.
 *
 * <p>Honors {@code dryRun}: when {@code "true"}, the streaming pass still fetches all
 * filmographies (there's no preview without the HTTP cost) but skips DB writes.
 */
@Slf4j
public final class EnrichmentClearMismatchedTask implements Task {

    public static final String ID = "enrichment.clear_mismatched";

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Clear mismatched javdb enrichments",
            "Validate enriched titles against each actress's javdb filmography. Clears wrong-slug "
                    + "rows + their grades (only when grade_source='enrichment') and re-enqueues for "
                    + "actress-anchored re-fetch. Resumable: per-title commits.",
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

        // ── Plan: load every actress with slug + every enriched title's actress set ──
        io.phaseStart("plan", "Loading actresses and enriched titles");
        List<ActressEntry> actresses = loadActressesWithSlug();
        Map<Long, TitleEntry> titles = loadEnrichedTitlesByActressSet(actresses);
        io.phaseEnd("plan", "ok",
                actresses.size() + " actress(es) · " + titles.size() + " enriched title(s) to validate");

        // ── Stream: fetch one actress at a time, evaluate ready titles, commit per-title ──
        io.phaseStart("stream", "Streaming filmographies + per-title cleanups");
        Map<Long, Map<String, String>> filmographies = new HashMap<>();
        Counters c = new Counters();
        for (int i = 0; i < actresses.size(); i++) {
            if (io.isCancellationRequested()) {
                io.phaseLog("stream", "Cancelled after " + i + " of " + actresses.size() + " actress(es)");
                break;
            }
            ActressEntry a = actresses.get(i);
            io.phaseProgress("stream", i, actresses.size(),
                    a.canonicalName + " (" + a.javdbSlug + ")");
            Map<String, String> film;
            try {
                film = slugResolver.filmographyOf(a.javdbSlug);
            } catch (RuntimeException e) {
                io.phaseLog("stream",
                        "FAIL filmography fetch for " + a.canonicalName + ": " + e.getMessage());
                c.fetchFails++;
                continue;
            }
            filmographies.put(a.actressId, film);
            c.fetched++;

            // Try to resolve every title linked to her — and any other title whose set is now complete.
            for (Long titleId : a.titleIds) {
                TitleEntry te = titles.get(titleId);
                if (te == null || te.resolved) continue;
                te.pendingActresses.remove(a.actressId);
                if (!te.pendingActresses.isEmpty()) continue;
                evaluateAndMaybeCleanup(te, filmographies, dryRun, c, io);
            }
        }
        io.phaseEnd("stream", "ok",
                c.fetched + " filmography(ies) · " + c.cleared + " cleared · "
                        + c.gradesCleared + " grade(s) reset · " + c.reenqueued + " re-enqueued"
                        + (c.fetchFails > 0 ? " · " + c.fetchFails + " fetch failure(s)" : ""));

        // ── Backstop: heuristic-flagged titles that were never resolved by streaming ──
        io.phaseStart("backstop", "Heuristic-flagged titles with no filmography coverage");
        int backstop = runBackstop(titles, dryRun, c, io);
        io.phaseEnd("backstop", "ok", backstop + " heuristic mismatch(es) cleared");

        if (dryRun) {
            io.phaseStart("dryrun", "Dry run summary");
            io.phaseEnd("dryrun", "ok",
                    "would clear " + c.cleared + " enrichment(s), " + c.gradesCleared
                            + " enrichment-source grade(s), " + c.reenqueued + " re-enqueue(s)");
        }
    }

    /**
     * Validate one fully-coverable title against all its slug-bearing actresses' filmographies.
     * If any filmography confirms the stored slug, leave the row intact; otherwise clear + enqueue.
     */
    private void evaluateAndMaybeCleanup(TitleEntry te,
                                         Map<Long, Map<String, String>> filmographies,
                                         boolean dryRun,
                                         Counters c,
                                         TaskIO io) {
        boolean confirmed = false;
        long anyActressId = -1L;
        for (Long actId : te.slugBearingActresses) {
            Map<String, String> f = filmographies.get(actId);
            if (f == null) continue;
            anyActressId = actId;
            if (Objects.equals(f.get(te.code), te.currentSlug)) {
                confirmed = true;
                break;
            }
        }
        te.resolved = true;
        if (confirmed) {
            c.confirmed++;
            return;
        }
        if (anyActressId < 0) return;   // shouldn't happen — defensive

        if (dryRun) {
            c.cleared++;
            c.gradesCleared += countEnrichmentGrade(te.titleId);
            c.reenqueued++;
            return;
        }
        try {
            int gradeNulled = clearOne(te.titleId);
            queue.enqueueTitleForce(te.titleId, anyActressId);
            c.cleared++;
            c.gradesCleared += gradeNulled;
            c.reenqueued++;
        } catch (RuntimeException e) {
            log.warn("EnrichmentClearMismatchedTask: failed for titleId={}: {}",
                    te.titleId, e.getMessage());
            io.phaseLog("stream", "FAIL titleId=" + te.titleId + " — " + e.getMessage());
        }
    }

    /**
     * Heuristic backstop: titles where the cast doesn't include the linked actress, AND the
     * title was never resolved by the streaming pass (typically because no linked actress has
     * a known javdb slug).
     */
    private int runBackstop(Map<Long, TitleEntry> titles, boolean dryRun, Counters c, TaskIO io) {
        List<HeuristicTarget> heuristic = loadHeuristicMismatches();
        int processed = 0;
        for (HeuristicTarget ht : heuristic) {
            if (io.isCancellationRequested()) {
                io.phaseLog("backstop", "Cancelled mid-backstop");
                break;
            }
            TitleEntry te = titles.get(ht.titleId);
            if (te != null && te.resolved) continue;   // already handled by streaming pass
            // Mark resolved so re-runs of the streaming pass won't attempt it.
            if (te != null) te.resolved = true;

            if (dryRun) {
                c.cleared++;
                c.gradesCleared += countEnrichmentGrade(ht.titleId);
                c.reenqueued++;
                processed++;
                continue;
            }
            try {
                int gradeNulled = clearOne(ht.titleId);
                queue.enqueueTitleForce(ht.titleId, ht.actressId);
                c.cleared++;
                c.gradesCleared += gradeNulled;
                c.reenqueued++;
                processed++;
            } catch (RuntimeException e) {
                io.phaseLog("backstop", "FAIL titleId=" + ht.titleId + " — " + e.getMessage());
            }
        }
        return processed;
    }

    /** Load every non-sentinel actress with a javdb slug + at least one enriched linked title. */
    private List<ActressEntry> loadActressesWithSlug() {
        Map<Long, ActressEntry> byId = new LinkedHashMap<>();
        jdbi.useHandle(h -> h.createQuery("""
                SELECT a.id            AS actress_id,
                       a.canonical_name AS canonical_name,
                       jas.javdb_slug  AS javdb_slug,
                       t.id            AS title_id
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
                        rs.getLong("title_id")
                })
                .forEach(row -> {
                    long actressId = (Long) row[0];
                    ActressEntry a = byId.computeIfAbsent(actressId,
                            id -> new ActressEntry(id, (String) row[1], (String) row[2]));
                    a.titleIds.add((Long) row[3]);
                }));
        return new ArrayList<>(byId.values());
    }

    /**
     * For every enriched title that has at least one slug-bearing actress, build a TitleEntry
     * with its slug-bearing actress set (the gate for collab evaluation).
     */
    private Map<Long, TitleEntry> loadEnrichedTitlesByActressSet(List<ActressEntry> actresses) {
        Map<Long, TitleEntry> titles = new LinkedHashMap<>();
        jdbi.useHandle(h -> h.createQuery("""
                SELECT t.id AS title_id, t.code AS code,
                       e.javdb_slug AS current_slug,
                       a.id AS actress_id
                FROM titles t
                JOIN title_javdb_enrichment e ON e.title_id = t.id
                JOIN title_actresses ta ON ta.title_id = t.id
                JOIN actresses a ON a.id = ta.actress_id
                JOIN javdb_actress_staging jas ON jas.actress_id = a.id
                WHERE COALESCE(a.is_sentinel, 0) = 0
                  AND jas.javdb_slug IS NOT NULL
                  AND jas.javdb_slug != ''
                """)
                .map((rs, ctx) -> new Object[]{
                        rs.getLong("title_id"),
                        rs.getString("code"),
                        rs.getString("current_slug"),
                        rs.getLong("actress_id")
                })
                .forEach(row -> {
                    long titleId = (Long) row[0];
                    TitleEntry te = titles.computeIfAbsent(titleId,
                            id -> new TitleEntry(id, (String) row[1], (String) row[2]));
                    te.slugBearingActresses.add((Long) row[3]);
                    te.pendingActresses.add((Long) row[3]);
                }));
        return titles;
    }

    /**
     * Heuristic-flagged titles (cast doesn't contain linked actress). Mirrors
     * {@link com.organizer3.mcp.tools.FindEnrichmentCastMismatchesTool}. Used by the backstop.
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
     * Per-title transactional clear: deletes the enrichment row, NULLs cached scalars on
     * {@code titles}, and resets the grade if it came from enrichment. Returns 1 if a grade
     * was reset, 0 otherwise. Each call commits independently — that's the resumability lever.
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
            return h.createUpdate("""
                    UPDATE titles SET grade = NULL, grade_source = NULL
                    WHERE id = :id AND grade_source = 'enrichment'
                    """).bind("id", titleId).execute();
        });
    }

    private int countEnrichmentGrade(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM titles WHERE id = :id AND grade_source = 'enrichment'")
                .bind("id", titleId).mapTo(Integer.class).one());
    }

    // ── value types ────────────────────────────────────────────────────────────

    private static final class ActressEntry {
        final long actressId;
        final String canonicalName;
        final String javdbSlug;
        final List<Long> titleIds = new ArrayList<>();
        ActressEntry(long actressId, String canonicalName, String javdbSlug) {
            this.actressId = actressId;
            this.canonicalName = canonicalName;
            this.javdbSlug = javdbSlug;
        }
    }

    private static final class TitleEntry {
        final long titleId;
        final String code;
        final String currentSlug;
        final Set<Long> slugBearingActresses = new LinkedHashSet<>();
        final Set<Long> pendingActresses = new LinkedHashSet<>();
        boolean resolved = false;
        TitleEntry(long titleId, String code, String currentSlug) {
            this.titleId = titleId;
            this.code = code;
            this.currentSlug = currentSlug;
        }
    }

    private record HeuristicTarget(long titleId, String code, long actressId) {}

    private static final class Counters {
        int fetched = 0;
        int fetchFails = 0;
        int cleared = 0;
        int gradesCleared = 0;
        int reenqueued = 0;
        int confirmed = 0;
    }
}
