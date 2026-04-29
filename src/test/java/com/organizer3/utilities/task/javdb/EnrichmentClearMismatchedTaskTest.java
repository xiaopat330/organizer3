package com.organizer3.utilities.task.javdb;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.enrichment.EnrichmentQueue;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentClearMismatchedTaskTest {

    private Connection connection;
    private Jdbi jdbi;
    private FakeJavdbClient client;
    private JavdbSlugResolver resolver;
    private EnrichmentQueue queue;
    private RevalidationPendingRepository revalidationPendingRepo;
    private EnrichmentClearMismatchedTask task;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        client = new FakeJavdbClient();
        resolver = new JavdbSlugResolver(client);
        queue = new EnrichmentQueue(jdbi, JavdbConfig.DEFAULTS);
        revalidationPendingRepo = new RevalidationPendingRepository(jdbi);
        task = new EnrichmentClearMismatchedTask(jdbi, resolver, queue, revalidationPendingRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void clearsTitleWhenStoredSlugDiffersFromFilmography() {
        long manaId = seedActress("Mana Sakura", "紗倉まな", "J9dd", false);
        long titleId = seedTitle("STAR-334");
        link(titleId, manaId);
        seedEnrichment(titleId, "wrongSlug",
                "[{\"slug\":\"wrongSlug\",\"name\":\"永野いち夏\"}]",
                "wrong-actress-original-title");
        // Filmography says STAR-334 → correctSlug for Mana Sakura.
        client.actressPage("J9dd", 1, html(false, entry("STAR-334", "correctSlug")));

        task.run(args(false), new CapturingIO());

        // Enrichment row gone, scalars cleared
        assertNull(getEnrichmentSlug(titleId));
        Map<String, Object> t = readTitle(titleId);
        assertNull(t.get("title_original"));
        assertNull(t.get("release_date"));

        // Re-enqueued
        assertTrue(hasPendingFetchJob(titleId), "title must be re-enqueued for fetch");
    }

    @Test
    void preservesEnrichmentWhenStoredSlugMatchesFilmography() {
        long manaId = seedActress("Mana Sakura", "紗倉まな", "J9dd", false);
        long titleId = seedTitle("STAR-100");
        link(titleId, manaId);
        seedEnrichment(titleId, "rightSlug",
                "[{\"slug\":\"rightSlug\",\"name\":\"紗倉まな\"}]", "Right title");
        client.actressPage("J9dd", 1, html(false, entry("STAR-100", "rightSlug")));

        task.run(args(false), new CapturingIO());

        assertEquals("rightSlug", getEnrichmentSlug(titleId), "row must remain intact");
        assertEquals("Right title", readTitle(titleId).get("title_original"));
        assertFalse(hasPendingFetchJob(titleId));
    }

    @Test
    void clearsEnrichmentSourceGradeButNotManualGrade() {
        long manaId = seedActress("Mana Sakura", "紗倉まな", "J9dd", false);

        long enrichTitle = seedTitle("STAR-1");
        link(enrichTitle, manaId);
        seedEnrichment(enrichTitle, "wrong",
                "[{\"slug\":\"wrong\",\"name\":\"X\"}]", "wrong");
        setGrade(enrichTitle, "B", "enrichment");

        long manualTitle = seedTitle("STAR-2");
        link(manualTitle, manaId);
        seedEnrichment(manualTitle, "alsoWrong",
                "[{\"slug\":\"alsoWrong\",\"name\":\"X\"}]", "wrong");
        setGrade(manualTitle, "A", "manual");

        client.actressPage("J9dd", 1, html(false));   // empty filmography → both miss

        task.run(args(false), new CapturingIO());

        Map<String, Object> e = readTitle(enrichTitle);
        assertNull(e.get("grade"), "enrichment-source grade must clear");
        assertNull(e.get("grade_source"));

        Map<String, Object> m = readTitle(manualTitle);
        assertEquals("A", m.get("grade"), "manual grade must survive");
        assertEquals("manual", m.get("grade_source"));
    }

    @Test
    void dryRunReportsButDoesNotMutate() {
        long manaId = seedActress("Mana Sakura", "紗倉まな", "J9dd", false);
        long titleId = seedTitle("STAR-334");
        link(titleId, manaId);
        seedEnrichment(titleId, "wrong",
                "[{\"slug\":\"wrong\",\"name\":\"X\"}]", "wrong");
        setGrade(titleId, "B", "enrichment");
        client.actressPage("J9dd", 1, html(false));

        CapturingIO io = new CapturingIO();
        task.run(args(true), io);

        // Row must still exist; grade must still be set.
        assertEquals("wrong", getEnrichmentSlug(titleId));
        assertEquals("B", readTitle(titleId).get("grade"));
        assertFalse(hasPendingFetchJob(titleId), "dryRun must not enqueue");
        assertTrue(io.summary("dryrun").contains("would clear"),
                "dryrun phase summary should report what would happen");
    }

    @Test
    void heuristicMismatchClearedEvenWithoutFilmography() {
        // Actress has NO javdb_actress_staging row → no filmography → heuristic backstop runs.
        long iori = seedActress("Iori Kogawa", "古川いおり", null, false);
        long titleId = seedTitle("MIDV-100");
        link(titleId, iori);
        seedEnrichment(titleId, "wrongSlug",
                "[{\"slug\":\"wrongSlug\",\"name\":\"全然違う名前\"}]",
                "wrong title original");

        task.run(args(false), new CapturingIO());

        assertNull(getEnrichmentSlug(titleId), "heuristic-flagged row must be cleared");
        assertTrue(hasPendingFetchJob(titleId));
    }

    @Test
    void sentinelActressIsNeverProcessed() {
        long various = seedActress("Various", "Various", "varSlug", true);
        long titleId = seedTitle("VAR-001");
        link(titleId, various);
        seedEnrichment(titleId, "anySlug",
                "[{\"slug\":\"anySlug\",\"name\":\"someone\"}]", "any title");
        client.actressPage("varSlug", 1, html(false));   // would otherwise flag it

        task.run(args(false), new CapturingIO());

        assertEquals("anySlug", getEnrichmentSlug(titleId), "sentinel must be excluded");
        assertFalse(hasPendingFetchJob(titleId));
    }

    @Test
    void collabTitleConfirmedByOneActressIsNotCleared() {
        long manaId = seedActress("Mana Sakura", "紗倉まな", "J9dd", false);
        long aoiId  = seedActress("Sora Aoi",   "蒼井そら", "K1aa", false);

        long titleId = seedTitle("COLLAB-1");
        link(titleId, manaId);
        link(titleId, aoiId);
        seedEnrichment(titleId, "collabSlug",
                "[{\"slug\":\"collabSlug\",\"name\":\"紗倉まな\"},{\"slug\":\"collabSlug\",\"name\":\"蒼井そら\"}]",
                "collab title");

        // Mana's filmography says STAR-1 → otherSlug (no entry for COLLAB-1)
        client.actressPage("J9dd", 1, html(false, entry("STAR-1", "otherSlug")));
        // Sora's filmography confirms COLLAB-1 → collabSlug (the stored slug)
        client.actressPage("K1aa", 1, html(false, entry("COLLAB-1", "collabSlug")));

        task.run(args(false), new CapturingIO());

        assertEquals("collabSlug", getEnrichmentSlug(titleId),
                "title must survive when at least one actress's filmography confirms the slug");
    }

    @Test
    void resumesAfterCancellation_perActressCommitsArePersistent() {
        // Two actresses, each with a wrong-slug title. Cancel after the first actress.
        // The first actress's title must already be cleared & re-enqueued (per-title commit
        // happened before cancellation took effect); the second's stays untouched.
        long manaId = seedActress("Aaa First",  "紗倉まな", "J9dd", false);
        long aoiId  = seedActress("Zzz Second", "蒼井そら", "K1aa", false);

        long firstTitle = seedTitle("STAR-1");
        link(firstTitle, manaId);
        seedEnrichment(firstTitle, "wrong1",
                "[{\"slug\":\"wrong1\",\"name\":\"X\"}]", "wrong1");
        long secondTitle = seedTitle("STAR-2");
        link(secondTitle, aoiId);
        seedEnrichment(secondTitle, "wrong2",
                "[{\"slug\":\"wrong2\",\"name\":\"X\"}]", "wrong2");

        client.actressPage("J9dd", 1, html(false));   // empty filmographies → mismatches
        client.actressPage("K1aa", 1, html(false));

        // Cancel-after-N=1: cancellation flag becomes true once the first actress is processed.
        CancellingIO io = new CancellingIO(1);
        task.run(args(false), io);

        // First title cleared (committed before cancel) — durable across simulated process death.
        assertNull(getEnrichmentSlug(firstTitle),
                "per-title commit must persist before cancellation interrupts the loop");
        assertTrue(hasPendingFetchJob(firstTitle));

        // Second title still has its (wrong) row; will be cleaned by the next run.
        assertEquals("wrong2", getEnrichmentSlug(secondTitle));
        assertFalse(hasPendingFetchJob(secondTitle));

        // Re-running picks up the remaining work.
        task.run(args(false), new CapturingIO());
        assertNull(getEnrichmentSlug(secondTitle), "re-run must clean the leftover");
        assertTrue(hasPendingFetchJob(secondTitle));
    }

    @Test
    void clearedTitleIsEnqueuedForRevalidation() {
        long manaId = seedActress("Mana Sakura", "紗倉まな", "J9dd", false);
        long titleId = seedTitle("STAR-555");
        link(titleId, manaId);
        seedEnrichment(titleId, "wrongSlug",
                "[{\"slug\":\"wrongSlug\",\"name\":\"X\"}]", "wrong title");
        client.actressPage("J9dd", 1, html(false)); // empty filmography → mismatch

        task.run(args(false), new CapturingIO());

        assertNull(getEnrichmentSlug(titleId), "enrichment must be cleared");
        int pendingCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM revalidation_pending WHERE title_id = ? AND reason = 'cleanup'")
                .bind(0, titleId).mapTo(Integer.class).one());
        assertEquals(1, pendingCount, "cleared title must be enqueued for revalidation with reason=cleanup");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long seedActress(String canonical, String stage, String javdbSlug, boolean sentinel) {
        long id = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel)
                VALUES (?, ?, 'LIBRARY', '2024-01-01', ?)
                """).bind(0, canonical).bind(1, stage).bind(2, sentinel ? 1 : 0)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        if (javdbSlug != null) {
            jdbi.useHandle(h -> h.createUpdate("""
                    INSERT INTO javdb_actress_staging (actress_id, javdb_slug, status)
                    VALUES (?, ?, 'slug_only')
                    """).bind(0, id).bind(1, javdbSlug).execute());
        }
        return id;
    }

    private long seedTitle(String code) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO titles (code, base_code, label, seq_num) VALUES (?, ?, ?, 1)
                """).bind(0, code).bind(1, code).bind(2, code.split("-")[0])
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void link(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses (title_id, actress_id) VALUES (?, ?)")
                .bind(0, titleId).bind(1, actressId).execute());
    }

    private void seedEnrichment(long titleId, String slug, String castJson, String titleOriginal) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO title_javdb_enrichment
                  (title_id, javdb_slug, fetched_at, cast_json, title_original, release_date)
                VALUES (?, ?, '2026-04-29T00:00:00Z', ?, ?, '2021-03-11')
                """).bind(0, titleId).bind(1, slug).bind(2, castJson).bind(3, titleOriginal)
                .execute());
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE titles SET title_original = ?, release_date = ? WHERE id = ?")
                .bind(0, titleOriginal).bind(1, "2021-03-11").bind(2, titleId).execute());
    }

    private void setGrade(long titleId, String grade, String source) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE titles SET grade = ?, grade_source = ? WHERE id = ?")
                .bind(0, grade).bind(1, source).bind(2, titleId).execute());
    }

    private String getEnrichmentSlug(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT javdb_slug FROM title_javdb_enrichment WHERE title_id = ?")
                .bind(0, titleId).mapTo(String.class).findOne().orElse(null));
    }

    private Map<String, Object> readTitle(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT title_original, release_date, grade, grade_source FROM titles WHERE id = ?")
                .bind(0, titleId).mapToMap().one());
    }

    private boolean hasPendingFetchJob(long titleId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT COUNT(*) FROM javdb_enrichment_queue
                WHERE job_type = 'fetch_title' AND target_id = ? AND status = 'pending'
                """).bind(0, titleId).mapTo(Integer.class).one()) > 0;
    }

    private static TaskInputs args(boolean dryRun) {
        return new TaskInputs(Map.of("dryRun", String.valueOf(dryRun)));
    }

    // ── HTML fixtures ────────────────────────────────────────────────────────

    private static String html(boolean hasNextPage, String... entriesHtml) {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (String e : entriesHtml) sb.append(e);
        sb.append("<nav class=\"pagination\">");
        if (hasNextPage) sb.append("<a rel=\"next\" class=\"pagination-next\" href=\"#\">Next</a>");
        sb.append("</nav></body></html>");
        return sb.toString();
    }

    private static String entry(String code, String slug) {
        return """
            <div class="item">
              <a href="/v/%s" class="box">
                <div class="video-title"><strong>%s</strong></div>
              </a>
            </div>
            """.formatted(slug, code);
    }

    // ── stubs ────────────────────────────────────────────────────────────────

    private static class FakeJavdbClient implements JavdbClient {
        private final Map<String, String> actressPageResults = new HashMap<>();

        void actressPage(String actressSlug, int page, String html) {
            actressPageResults.put(actressSlug + "?page=" + page, html);
        }

        @Override public String searchByCode(String code)   { return ""; }
        @Override public String fetchTitlePage(String slug) { return ""; }
        @Override public String fetchActressPage(String slug) { return fetchActressPage(slug, 1); }
        @Override public String fetchActressPage(String slug, int page) {
            return actressPageResults.getOrDefault(slug + "?page=" + page, "<html><body></body></html>");
        }
    }

    /** Returns true once {@code phaseProgress} has been called {@code triggerAt} times. */
    static final class CancellingIO extends CapturingIO {
        private final int triggerAt;
        private int progressCalls = 0;
        private boolean cancelled = false;
        CancellingIO(int triggerAt) { this.triggerAt = triggerAt; }
        @Override public void phaseProgress(String id, int c, int t, String detail) {
            progressCalls++;
            if (progressCalls >= triggerAt) cancelled = true;
        }
        @Override public boolean isCancellationRequested() { return cancelled; }
    }

    static class CapturingIO implements TaskIO {
        private final Map<String, String> statuses  = new HashMap<>();
        private final Map<String, String> summaries = new HashMap<>();
        private final Map<String, List<String>> logs = new HashMap<>();

        @Override public void phaseStart(String id, String label)  { logs.put(id, new ArrayList<>()); }
        @Override public void phaseProgress(String id, int c, int t, String detail) {}
        @Override public void phaseLog(String id, String line) {
            logs.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
        }
        @Override public void phaseEnd(String id, String status, String summary) {
            statuses.put(id, status); summaries.put(id, summary);
        }
        String status(String phase)  { return statuses.getOrDefault(phase, ""); }
        String summary(String phase) { return summaries.getOrDefault(phase, ""); }
    }
}
