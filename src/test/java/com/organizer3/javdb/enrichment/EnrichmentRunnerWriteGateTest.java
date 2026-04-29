package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Wave 1C+1D+1E write-time gate and sentinel short-circuit in EnrichmentRunner.
 *
 * <p>Covers all 7 gate decision rows plus the sentinel short-circuit. Uses real in-memory SQLite
 * and a fake JavdbClient whose title HTML is constructed to match each scenario.
 */
class EnrichmentRunnerWriteGateTest {

    @TempDir
    Path dataDir;

    private Jdbi jdbi;
    private Connection connection;
    private EnrichmentQueue queue;
    private JavdbStagingRepository stagingRepo;
    private JavdbEnrichmentRepository enrichmentRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private CastMatcher castMatcher;

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        ObjectMapper mapper = new ObjectMapper();
        queue = new EnrichmentQueue(jdbi, CONFIG);
        stagingRepo = new JavdbStagingRepository(jdbi, mapper, dataDir);
        enrichmentRepo = new JavdbEnrichmentRepository(jdbi, mapper, new TitleEffectiveTagsService(jdbi), new EnrichmentHistoryRepository(jdbi, mapper));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        actressRepo = new JdbiActressRepository(jdbi);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        reviewQueueRepo = new EnrichmentReviewQueueRepository(jdbi);
        castMatcher = new CastMatcher(actressRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────────

    private long seedTitle(String code) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (code, base_code, label, seq_num) VALUES (:c, :c, 'X', 1)")
                .bind("c", code).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long seedActress(String canonical, String stage, boolean sentinel) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel) " +
                "VALUES (:c, :s, 'LIBRARY', '2024-01-01', :sent)")
                .bind("c", canonical).bind("s", stage).bind("sent", sentinel ? 1 : 0)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void link(long titleId, long actressId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));
    }

    private EnrichmentRunner makeRunner(JavdbClient client) {
        return new EnrichmentRunner(
                CONFIG, client, new JavdbSlugResolver(client),
                new JavdbExtractor(), new JavdbProjector(new ObjectMapper()),
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null,
                titleActressRepo, reviewQueueRepo, castMatcher, jdbi);
    }

    /** Builds HTML for a title page with the given cast names (Actors block). */
    private String titleHtmlWithCast(String... names) {
        var sb = new StringBuilder("<html><body><div class=\"movie-panel-info\">");
        sb.append("<div class=\"panel-block\"><strong>Actors:</strong><span class=\"value\">");
        for (int i = 0; i < names.length; i++) {
            sb.append("<a href=\"/actors/fk0").append(i + 1).append("\">").append(names[i]).append("</a> ");
        }
        sb.append("</span></div></div></body></html>");
        return sb.toString();
    }

    /** HTML where the Actor block exists but contains no entries (genuine empty cast). */
    private String titleHtmlEmptyCast() {
        return "<html><body><div class=\"movie-panel-info\">" +
               "<div class=\"panel-block\"><strong>Actors:</strong>" +
               "<span class=\"value\"></span></div></div></body></html>";
    }

    /** HTML with no Actor block at all (same end-result as empty cast for the extractor). */
    private String titleHtmlNoCast() {
        return "<html><body><div class=\"movie-panel-info\"></div></body></html>";
    }

    private JavdbClient fakeClient(String slug, String titleHtml) {
        String searchHtml = "<a href=\"/v/" + slug + "\">DUMMY</a>";
        return new JavdbClient() {
            @Override public String searchByCode(String code) { return searchHtml; }
            @Override public String fetchTitlePage(String s)  { return titleHtml; }
            @Override public String fetchActressPage(String s) { throw new AssertionError("not expected"); }
        };
    }

    private Optional<java.util.Map<String, Object>> enrichmentRow(long titleId) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT confidence, resolver_source, cast_validated FROM title_javdb_enrichment WHERE title_id = :id")
                .bind("id", titleId).mapToMap().findOne());
    }

    // ─── Row 1: castParseFailed → fetch_failed ────────────────────────────────────

    @Test
    void gate_row1_castParseFailed_routesToFetchFailed() throws InterruptedException {
        // Simulate a parse failure via a throwable-injected extractor subclass
        long actressId = seedActress("Yui Hatano", "Yui Hatano", false);
        long titleId = seedTitle("YUI-001");
        link(titleId, actressId);

        // Seed an actress staging so the filmography resolver path is used
        stagingRepo.upsertActressSlugOnly(actressId, "yui01", "YUI-001");

        // Build a filmography that maps the code to a slug
        var filmographyRepo = new JdbiJavdbActressFilmographyRepository(jdbi, new RevalidationPendingRepository(jdbi));
        filmographyRepo.upsertFilmography("yui01", new FetchResult(
                java.time.Instant.now().toString(), 1, null, "http",
                java.util.List.of(new FilmographyEntry("YUI-001", "yui_slug"))));

        // Override extractor to inject castParseFailed=true
        JavdbExtractor failingExtractor = new JavdbExtractor() {
            @Override
            public TitleExtract extractTitle(String html, String code, String slug) {
                TitleExtract base = super.extractTitle(html, code, slug);
                return new TitleExtract(base.code(), base.javdbSlug(), base.titleOriginal(),
                        base.releaseDate(), base.durationMinutes(), base.maker(), base.publisher(),
                        base.series(), base.ratingAvg(), base.ratingCount(), base.tags(), base.cast(),
                        base.coverUrl(), base.thumbnailUrls(), base.fetchedAt(), false, true);
            }
        };

        JavdbClient client = new JavdbClient() {
            @Override public String searchByCode(String code) { return "<a href=\"/v/yui_slug\">YUI-001</a>"; }
            @Override public String fetchTitlePage(String s)  { return titleHtmlWithCast("Yui Hatano"); }
            @Override public String fetchActressPage(String s) { throw new AssertionError("not expected"); }
        };

        var slugResolver = new JavdbSlugResolver(client, filmographyRepo,
                new com.organizer3.javdb.JavdbConfig(true, 1.0, 90, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null));

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, client, slugResolver,
                failingExtractor, new JavdbProjector(new ObjectMapper()),
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null,
                titleActressRepo, reviewQueueRepo, castMatcher, jdbi);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        assertTrue(enrichmentRow(titleId).isEmpty(), "no enrichment row should be written on castParseFailed");
        assertTrue(reviewQueueRepo.hasOpen(titleId, "fetch_failed"));
    }

    // ─── Row 2: ACTRESS_FILMOGRAPHY + castEmpty → HIGH ───────────────────────────

    @Test
    void gate_row2_filmographySource_emptyCast_writesHigh() throws InterruptedException {
        long actressId = seedActress("Mana Sakura", "桜空もも", false);
        long titleId = seedTitle("IPX-100");
        link(titleId, actressId);

        stagingRepo.upsertActressSlugOnly(actressId, "mana01", "IPX-100");
        var filmographyRepo = new JdbiJavdbActressFilmographyRepository(jdbi, new RevalidationPendingRepository(jdbi));
        filmographyRepo.upsertFilmography("mana01", new FetchResult(
                java.time.Instant.now().toString(), 1, null, "http",
                java.util.List.of(new FilmographyEntry("IPX-100", "ipx100"))));

        JavdbClient client = fakeClient("ipx100", titleHtmlEmptyCast());
        var slugResolver = new JavdbSlugResolver(client, filmographyRepo,
                new com.organizer3.javdb.JavdbConfig(true, 1.0, 90, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null));
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, client, slugResolver,
                new JavdbExtractor(), new JavdbProjector(new ObjectMapper()),
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null,
                titleActressRepo, reviewQueueRepo, castMatcher, jdbi);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        var row = enrichmentRow(titleId);
        assertTrue(row.isPresent(), "enrichment row should be written for genuine empty cast");
        assertEquals("HIGH", row.get().get("confidence"));
        assertEquals(1, ((Number) row.get().get("cast_validated")).intValue());
        assertFalse(reviewQueueRepo.hasOpen(titleId, "cast_anomaly"));
    }

    // ─── Row 3: ACTRESS_FILMOGRAPHY + non-empty cast + actress in cast → HIGH ────

    @Test
    void gate_row3_filmographySource_actressInCast_writesHigh() throws InterruptedException {
        long actressId = seedActress("Yui Hatano", "波多野結衣", false);
        long titleId = seedTitle("PRED-100");
        link(titleId, actressId);

        stagingRepo.upsertActressSlugOnly(actressId, "yui01", "PRED-100");
        var filmographyRepo = new JdbiJavdbActressFilmographyRepository(jdbi, new RevalidationPendingRepository(jdbi));
        filmographyRepo.upsertFilmography("yui01", new FetchResult(
                java.time.Instant.now().toString(), 1, null, "http",
                java.util.List.of(new FilmographyEntry("PRED-100", "pred100"))));

        JavdbClient client = fakeClient("pred100", titleHtmlWithCast("波多野結衣"));
        var slugResolver = new JavdbSlugResolver(client, filmographyRepo,
                new com.organizer3.javdb.JavdbConfig(true, 1.0, 90, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null));
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, client, slugResolver,
                new JavdbExtractor(), new JavdbProjector(new ObjectMapper()),
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null,
                titleActressRepo, reviewQueueRepo, castMatcher, jdbi);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        var row = enrichmentRow(titleId);
        assertTrue(row.isPresent());
        assertEquals("HIGH", row.get().get("confidence"));
        assertEquals(1, ((Number) row.get().get("cast_validated")).intValue());
    }

    // ─── Row 4: ACTRESS_FILMOGRAPHY + non-empty cast + actress NOT in cast → LOW + cast_anomaly ─

    @Test
    void gate_row4_filmographySource_actressNotInCast_writesLowAndQueues() throws InterruptedException {
        long actressId = seedActress("Yui Hatano", "波多野結衣", false);
        long titleId = seedTitle("PRED-200");
        link(titleId, actressId);

        stagingRepo.upsertActressSlugOnly(actressId, "yui01", "PRED-200");
        var filmographyRepo = new JdbiJavdbActressFilmographyRepository(jdbi, new RevalidationPendingRepository(jdbi));
        filmographyRepo.upsertFilmography("yui01", new FetchResult(
                java.time.Instant.now().toString(), 1, null, "http",
                java.util.List.of(new FilmographyEntry("PRED-200", "pred200"))));

        // Cast contains a different actress — mismatch
        JavdbClient client = fakeClient("pred200", titleHtmlWithCast("別の女優"));
        var slugResolver = new JavdbSlugResolver(client, filmographyRepo,
                new com.organizer3.javdb.JavdbConfig(true, 1.0, 90, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null));
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, client, slugResolver,
                new JavdbExtractor(), new JavdbProjector(new ObjectMapper()),
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null,
                titleActressRepo, reviewQueueRepo, castMatcher, jdbi);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        var row = enrichmentRow(titleId);
        assertTrue(row.isPresent(), "enrichment row should be written even on mismatch (LOW)");
        assertEquals("LOW", row.get().get("confidence"));
        assertEquals(0, ((Number) row.get().get("cast_validated")).intValue());
        assertTrue(reviewQueueRepo.hasOpen(titleId, "cast_anomaly"), "cast_anomaly should be queued");
    }

    // ─── Row 5: CODE_SEARCH_FALLBACK + no real linked actress → MEDIUM ────────────

    @Test
    void gate_row5_codeSearchFallback_noLinkedActress_writesMedium() throws InterruptedException {
        long titleId = seedTitle("COL-001");
        // No actress linked — collection-style or sentinel-only title

        JavdbClient client = fakeClient("col001", titleHtmlWithCast("SomeActress"));
        EnrichmentRunner runner = makeRunner(client);

        // Enqueue with actressId=0 (no actress anchor)
        queue.enqueueTitle(EnrichmentJob.SOURCE_COLLECTION, titleId, null);
        runner.runOneStep();

        var row = enrichmentRow(titleId);
        assertTrue(row.isPresent(), "enrichment should be written");
        assertEquals("MEDIUM", row.get().get("confidence"));
        assertEquals(0, ((Number) row.get().get("cast_validated")).intValue());
    }

    // ─── Row 6: CODE_SEARCH_FALLBACK + real linked actress in cast → MEDIUM ────────

    @Test
    void gate_row6_codeSearchFallback_linkedActressInCast_writesMedium() throws InterruptedException {
        long actressId = seedActress("Alice Mizuki", "Alice Mizuki", false);
        long titleId = seedTitle("ALI-001");
        link(titleId, actressId);

        JavdbClient client = fakeClient("ali001", titleHtmlWithCast("Alice Mizuki"));
        EnrichmentRunner runner = makeRunner(client);

        // Use actress-driven enqueue but actress has no staging slug → falls to code-search
        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        var row = enrichmentRow(titleId);
        assertTrue(row.isPresent());
        assertEquals("MEDIUM", row.get().get("confidence"));
        assertEquals("code_search_fallback", row.get().get("resolver_source"));
    }

    // ─── Row 7: CODE_SEARCH_FALLBACK + real linked actress NOT in cast → ambiguous ─

    @Test
    void gate_row7_codeSearchFallback_linkedActressNotInCast_routesToAmbiguous() throws InterruptedException {
        long actressId = seedActress("Alice Mizuki", "Alice Mizuki", false);
        long titleId = seedTitle("ALI-002");
        link(titleId, actressId);

        // Cast contains somebody else, not Alice
        JavdbClient client = fakeClient("ali002", titleHtmlWithCast("Completely Different Actress"));
        EnrichmentRunner runner = makeRunner(client);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        assertTrue(enrichmentRow(titleId).isEmpty(), "no enrichment row for ambiguous code-search mismatch");
        assertTrue(reviewQueueRepo.hasOpen(titleId, "ambiguous"), "title should be queued as ambiguous");
    }

    // ─── 1D: Sentinel short-circuit ──────────────────────────────────────────────

    @Test
    void sentinelShortCircuit_1d_skipsFetchAndQueuesAmbiguous() throws InterruptedException {
        long sentinelId = seedActress("Various", "Various", true);  // is_sentinel=1
        long titleId = seedTitle("VAR-001");
        link(titleId, sentinelId);

        boolean[] fetchCalled = {false};
        JavdbClient client = new JavdbClient() {
            @Override public String searchByCode(String code)  { fetchCalled[0] = true; return ""; }
            @Override public String fetchTitlePage(String s)   { fetchCalled[0] = true; return ""; }
            @Override public String fetchActressPage(String s) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = makeRunner(client);
        queue.enqueueTitle(titleId, sentinelId);
        runner.runOneStep();

        assertFalse(fetchCalled[0], "HTTP fetch must not occur for a sentinel actress (1D short-circuit)");
        assertTrue(enrichmentRow(titleId).isEmpty(), "no enrichment row should be written for sentinel");
        assertTrue(reviewQueueRepo.hasOpen(titleId, "ambiguous"), "sentinel should be queued as ambiguous");
        assertEquals(0, queue.countPending(), "sentinel job should be permanently failed");
    }

    @Test
    void sentinelShortCircuit_doesNotApplyToCollectionJobs() throws InterruptedException {
        long sentinelId = seedActress("Various", "Various", true);
        long realId     = seedActress("Real Actress", "Real Actress", false);
        long titleId = seedTitle("MIX-001");
        link(titleId, sentinelId);
        link(titleId, realId);

        // Collection job has actressId=0 so sentinel check is skipped
        JavdbClient client = fakeClient("mix001", titleHtmlWithCast("Various", "Real Actress"));
        EnrichmentRunner runner = makeRunner(client);

        queue.enqueueTitle(EnrichmentJob.SOURCE_COLLECTION, titleId, null);
        runner.runOneStep();

        // Enrichment proceeds normally — code-search fallback with real actress in cast → MEDIUM
        assertTrue(enrichmentRow(titleId).isPresent(), "collection job should not be blocked by sentinel");
    }
}
