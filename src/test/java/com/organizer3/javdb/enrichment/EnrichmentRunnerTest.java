package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.JavdbConfig;
import com.organizer3.javdb.JavdbForbiddenException;
import com.organizer3.javdb.JavdbRateLimitException;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentRunnerTest {

    @TempDir
    Path dataDir;

    private Jdbi jdbi;
    private Connection connection;
    private EnrichmentQueue queue;
    private JavdbStagingRepository stagingRepo;
    private JavdbEnrichmentRepository enrichmentRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiActressRepository actressRepo;
    private JavdbExtractor extractor;
    private JavdbProjector projector;

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        queue = new EnrichmentQueue(jdbi, CONFIG);
        ObjectMapper mapper = new ObjectMapper();
        stagingRepo = new JavdbStagingRepository(jdbi, mapper, dataDir);
        enrichmentRepo = new JavdbEnrichmentRepository(jdbi, mapper, new com.organizer3.db.TitleEffectiveTagsService(jdbi), new EnrichmentHistoryRepository(jdbi, mapper));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        actressRepo = new JdbiActressRepository(jdbi);
        extractor = new JavdbExtractor();
        projector = new JavdbProjector(mapper);
    }

    @Test
    void fetchTitle_writesEnrichment_andRecordsActressSlug() throws Exception {
        // Seed a title and actress whose stage name matches the cast in title_detail.html
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('DV-948', 'DV', 'DV', 948)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('麻美ゆま', '麻美ゆま', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        // Fake client: search returns a page with /v/deD0v; title page returns the fixture HTML
        String searchHtml = "<a href=\"/v/deD0v\">DV-948</a>";
        String titleHtml = loadFixture("title_detail.html");
        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { return searchHtml; }
            @Override public String fetchTitlePage(String slug) { return titleHtml; }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        // Enrichment row was created
        var enrichRow = jdbi.withHandle(h -> h.createQuery(
                "SELECT javdb_slug, raw_path FROM title_javdb_enrichment WHERE title_id = :id")
                .bind("id", titleId)
                .mapToMap()
                .findOne());
        assertTrue(enrichRow.isPresent(), "title_javdb_enrichment row should exist");
        assertEquals("deD0v", enrichRow.get().get("javdb_slug"));
        String rawPath = (String) enrichRow.get().get("raw_path");
        assertNotNull(rawPath);

        // Raw JSON file was written to disk
        Path rawFile = dataDir.resolve(rawPath);
        assertTrue(Files.exists(rawFile));

        // Tag rows were normalized: at least one tag definition + one assignment exist
        int tagAssignments = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_enrichment_tags WHERE title_id = :id")
                .bind("id", titleId).mapTo(Integer.class).one());
        assertTrue(tagAssignments > 0, "tag assignments should be populated from extract.tags()");

        // Actress slug was resolved from cast list
        Optional<JavdbActressStagingRow> actressRow = stagingRepo.findActressStaging(actressId);
        assertTrue(actressRow.isPresent());
        assertEquals("ex3z", actressRow.get().javdbSlug());
        assertEquals("DV-948", actressRow.get().sourceTitleCode());

        // Profile job was auto-enqueued (completion hook); original title job is done
        Optional<EnrichmentJob> profileJob = queue.claimNextJob();
        assertTrue(profileJob.isPresent());
        assertEquals(EnrichmentJob.FETCH_ACTRESS_PROFILE, profileJob.get().jobType());
        assertEquals(actressId, profileJob.get().actressId());
    }

    @Test
    void fetchTitle_marksNotFound_whenSearchReturnsNoResults() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('ABC-001', 'ABC', 'ABC', 1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Test Actress', 'Test Actress', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { return "<html><body>no results</body></html>"; }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        // No enrichment row is created for not_found (queue tracks the failure)
        int enrichRows = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM title_javdb_enrichment WHERE title_id = :id")
                .bind("id", titleId).mapTo(Integer.class).one());
        assertEquals(0, enrichRows, "no enrichment row should be created for not_found");
        assertEquals(0, queue.countPending());
    }

    // ── 403 / 429 pause ────────────────────────────────────────────────────────

    @Test
    void fetchTitle_pausesAndReleasesToRetry_on403() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('ABC-001', 'ABC', 'ABC', 1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Test', 'Test', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { throw new JavdbForbiddenException("https://javdb.com/search?q=" + code); }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        // Job should be released back to pending (not permanently failed)
        assertEquals(1, queue.countPending(), "403 should release job to retry, not permanently fail it");
    }

    @Test
    void fetchTitle_pausesAndReleasesToRetry_on429() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('ABC-002', 'ABC', 'ABC', 2)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Test2', 'Test2', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { throw new JavdbRateLimitException("https://javdb.com/search?q=" + code); }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        assertEquals(1, queue.countPending(), "429 should release job to retry, not permanently fail it");
    }

    @Test
    void forceResume_clearsPauseAndBackoffCounters_after429() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('ABC-003', 'ABC', 'ABC', 3)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Test3', 'Test3', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        JavdbClient rateLimitClient = new JavdbClient() {
            @Override public String searchByCode(String code) { throw new JavdbRateLimitException("https://javdb.com/search?q=" + code); }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, rateLimitClient, new JavdbSlugResolver(rateLimitClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        // Verify pause was set
        assertTrue(java.time.Instant.now().isBefore(runner.getPauseUntil()), "pause should be active after 429");
        assertEquals(1, runner.getConsecutiveRateLimitHits());
        assertNotNull(runner.getPauseReason());

        runner.forceResume();

        assertFalse(java.time.Instant.now().isBefore(runner.getPauseUntil()), "pause should be cleared after forceResume");
        assertEquals(0, runner.getConsecutiveRateLimitHits());
        assertNull(runner.getPauseReason());
    }

    // ── lookupCode ─────────────────────────────────────────────────────────────

    @Test
    void lookupCode_stripsUnderscoreSuffix() {
        assertEquals("SONE-038", EnrichmentRunner.lookupCode("SONE-038_4K"));
    }

    @Test
    void lookupCode_stripsDashSuffix() {
        assertEquals("SONE-038", EnrichmentRunner.lookupCode("SONE-038-4K"));
    }

    @Test
    void lookupCode_leavesPlainCodeUnchanged() {
        assertEquals("DV-948", EnrichmentRunner.lookupCode("DV-948"));
    }

    @Test
    void lookupCode_leavesMultiDigitCodeUnchanged() {
        assertEquals("ABP-123", EnrichmentRunner.lookupCode("ABP-123"));
    }

    @Test
    void fetchTitle_searchesWithStrippedCode_whenTitleHasSuffix() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('SONE-038_4K', 'SONE', 'SONE', 38)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Test', 'Test', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        var capturedCode = new String[1];
        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) {
                capturedCode[0] = code;
                return "<html><body>no results</body></html>";
            }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        assertEquals("SONE-038", capturedCode[0]);
    }

    // ── single-cast slug fallback ───────────────────────────────────────────────

    @Test
    void fetchTitle_assignsSlug_bySingleCastAssumption_whenNameDoesNotMatch() throws Exception {
        // Actress has a romanized name that won't match the Japanese cast entry
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('XYZ-001', 'XYZ', 'XYZ', 1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Yuma Asami', 'Yuma Asami', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        // Seed an enrichment row directly with a single-entry cast_json (Japanese name won't
        // match "Yuma Asami"), then test the backfill path
        String castJson = "[{\"slug\":\"ab12\",\"name\":\"あさみゆま\",\"gender\":\"F\"}]";
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json) VALUES (?, 'ab99', '2026-04-26T00:00:00Z', ?)",
                titleId, castJson));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, null, new JavdbSlugResolver(null), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        runner.backfillActressSlugsFromEnrichment();

        Optional<JavdbActressStagingRow> staging = stagingRepo.findActressStaging(actressId);
        assertTrue(staging.isPresent(), "slug should be backfilled from single-cast enrichment");
        assertEquals("ab12", staging.get().javdbSlug());

        // Profile fetch should be enqueued
        Optional<EnrichmentJob> profileJob = queue.claimNextJob();
        assertTrue(profileJob.isPresent());
        assertEquals(EnrichmentJob.FETCH_ACTRESS_PROFILE, profileJob.get().jobType());
        assertEquals(actressId, profileJob.get().actressId());
    }

    @Test
    void backfill_skipsActressesAlreadyHavingStaging() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('XYZ-002', 'XYZ', 'XYZ', 2)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Already Staged', 'Already Staged', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json) VALUES (?, 'xx99', '2026-04-26T00:00:00Z', '[{\"slug\":\"xx12\",\"name\":\"テスト\",\"gender\":\"F\"}]')",
                titleId));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));
        // Pre-existing staging row
        stagingRepo.upsertActressSlugOnly(actressId, "existing-slug", "XYZ-002");

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, null, new JavdbSlugResolver(null), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        runner.backfillActressSlugsFromEnrichment();

        // Existing slug should be unchanged
        assertEquals("existing-slug", stagingRepo.findActressStaging(actressId).orElseThrow().javdbSlug());
        // No profile job enqueued (actress already had staging)
        assertEquals(0, queue.countPending());
    }

    @Test
    void backfill_skipsAmbiguousMultiCastTitles() throws Exception {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('XYZ-003', 'XYZ', 'XYZ', 3)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at) VALUES ('Multi Cast', 'Multi Cast', 'LIBRARY', '2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        String multiCastJson = "[{\"slug\":\"aa1\",\"name\":\"女優A\",\"gender\":\"F\"},{\"slug\":\"bb2\",\"name\":\"女優B\",\"gender\":\"F\"}]";
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_javdb_enrichment (title_id, javdb_slug, fetched_at, cast_json) VALUES (?, 'mc99', '2026-04-26T00:00:00Z', ?)",
                titleId, multiCastJson));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));

        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, null, new JavdbSlugResolver(null), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        runner.backfillActressSlugsFromEnrichment();

        // Multi-cast title — ambiguous, should not be backfilled
        assertTrue(stagingRepo.findActressStaging(actressId).isEmpty());
        assertEquals(0, queue.countPending());
    }

    // ── ProfileChainGate integration ──────────────────────────────────────────

    /**
     * Regression: actress-driven flow still auto-chains a profile fetch even when the actress
     * is below the title-count threshold that would block a title-driven job.
     */
    @Test
    void actressDrivenJob_alwaysChains_regardlessOfGate() throws Exception {
        // Non-sentinel actress with only 1 title — below the profileChainMinTitles=3 threshold.
        // The ProfileChainGate would block chaining for a title-driven job, but actress-driven
        // jobs bypass the threshold check.
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel) " +
                               "VALUES ('Rika Takasugi', 'Rika Takasugi', 'LIBRARY', '2024-01-01', 0)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('VAR-001', 'VAR', 'VAR', 1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (actress_id, title_id) VALUES (?,?)", actressId, titleId));

        // Title page returns a single cast entry matching the actress's stage name so the gate
        // reaches the CODE_SEARCH_FALLBACK "no real linked actress in cast" row and writes MEDIUM.
        // The actress is in the cast, so row 6 applies: write MEDIUM + chain profile.
        String searchHtml = "<a href=\"/v/var01\">VAR-001</a>";
        String titleHtml = buildFakeTitleHtml("Rika Takasugi");
        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { return searchHtml; }
            @Override public String fetchTitlePage(String slug) { return titleHtml; }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        ProfileChainGate gate = new ProfileChainGate(jdbi, CONFIG);
        var titleActressRepo = new com.organizer3.repository.jdbi.JdbiTitleActressRepository(jdbi);
        var castMatcher = new CastMatcher(actressRepo);
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, gate, titleActressRepo, null, castMatcher, jdbi, null, null);

        // Actress-driven enqueue (existing method).
        queue.enqueueTitle(titleId, actressId);
        runner.runOneStep();

        // Profile fetch must be enqueued — actress-driven flow bypasses the title-count threshold.
        Optional<EnrichmentJob> profileJob = queue.claimNextJob();
        assertTrue(profileJob.isPresent(), "actress-driven job should always chain profile fetch");
        assertEquals(EnrichmentJob.FETCH_ACTRESS_PROFILE, profileJob.get().jobType());
    }

    /**
     * Title-driven job: sentinel actress in cast → profile fetch NOT chained.
     */
    @Test
    void titleDrivenJob_sentinelActress_doesNotChainProfileFetch() throws Exception {
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel) " +
                               "VALUES ('Amateur', 'Amateur', 'LIBRARY', '2024-01-01', 1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES ('AMT-001', 'AMT', 'AMT', 1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (actress_id, title_id) VALUES (?,?)", actressId, titleId));

        String searchHtml = "<a href=\"/v/amt01\">AMT-001</a>";
        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { return searchHtml; }
            @Override public String fetchTitlePage(String slug) { return buildFakeTitleHtml("Amateur"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        ProfileChainGate gate = new ProfileChainGate(jdbi, CONFIG);
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, gate, null, null, null, jdbi, null, null);

        queue.enqueueTitle(EnrichmentJob.SOURCE_POOL, titleId, actressId);
        runner.runOneStep();

        // Sentinel → no profile chain.
        assertEquals(0, queue.countPending(), "sentinel actress in title-driven flow must not trigger profile chain");
    }

    /**
     * Title-driven job: real actress with ≥3 titles → profile fetch chained.
     */
    @Test
    void titleDrivenJob_eligibleActress_chainsProfileFetch() throws Exception {
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel) " +
                               "VALUES ('Yui Hatano', 'Yui Hatano', 'LIBRARY', '2024-01-01', 0)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        // Seed 3 titles so the actress meets the threshold.
        for (int i = 1; i <= 3; i++) {
            final int seq = i;
            long tid = jdbi.withHandle(h ->
                    h.createUpdate("INSERT INTO titles (code, base_code, label, seq_num) VALUES (:c,:c,'PRED',:s)")
                            .bind("c", "PRED-00" + seq)
                            .bind("s", seq)
                            .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
            jdbi.useHandle(h -> h.execute("INSERT INTO title_actresses (actress_id, title_id) VALUES (?,?)", actressId, tid));
        }
        // The title being enriched is one of those three.
        long enrichTitleId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM titles WHERE code = 'PRED-001'").mapTo(Long.class).one());

        String searchHtml = "<a href=\"/v/pred01\">PRED-001</a>";
        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) { return searchHtml; }
            @Override public String fetchTitlePage(String slug) { return buildFakeTitleHtml("Yui Hatano"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };

        ProfileChainGate gate = new ProfileChainGate(jdbi, CONFIG);
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, gate, null, null, null, jdbi, null, null);

        queue.enqueueTitle(EnrichmentJob.SOURCE_RECENT, enrichTitleId, actressId);
        runner.runOneStep();

        Optional<EnrichmentJob> profileJob = queue.claimNextJob();
        assertTrue(profileJob.isPresent(), "eligible actress in title-driven flow should chain profile fetch");
        assertEquals(EnrichmentJob.FETCH_ACTRESS_PROFILE, profileJob.get().jobType());
        assertEquals(actressId, profileJob.get().actressId());
    }

    // ── Collection-source post-fetch hook (M3b) ────────────────────────────

    /** Collection job: javdb cast lists 2 actresses, both eligible → both get slugs and chain profiles. */
    @Test
    void collectionJob_allEligibleCastChainProfileFetches() throws Exception {
        long alice = seedActress("Alice", false);
        long bob   = seedActress("Bob",   false);
        // Both meet threshold (3 titles each, with overlap on the collection title we'll enrich).
        seedActressCredits(alice, 2);   // 2 padding titles
        seedActressCredits(bob,   2);
        long colTitleId = seedTitle("DUO-001");
        link(colTitleId, alice);
        link(colTitleId, bob);

        JavdbClient fakeClient = multiCastClient("DUO-001", "Alice", "Bob");
        EnrichmentRunner runner = collectionRunner(fakeClient);

        queue.enqueueTitle(EnrichmentJob.SOURCE_COLLECTION, colTitleId, null);
        runner.runOneStep();

        // Two profile-fetch jobs queued (one per cast member).
        var jobs = drainAllProfileJobs();
        assertEquals(2, jobs.size(), "both eligible cast members should chain a profile fetch");
        var actressIds = jobs.stream().map(EnrichmentJob::actressId).sorted().toList();
        assertEquals(java.util.List.of(Math.min(alice, bob), Math.max(alice, bob)), actressIds);
    }

    /** Collection job with mixed cast: eligible actress chains, sentinel does not. */
    @Test
    void collectionJob_sentinelCastDoesNotChain() throws Exception {
        long real     = seedActress("RealActress", false);
        long sentinel = seedActress("Various",     true);   // sentinel
        seedActressCredits(real, 2);
        long colTitleId = seedTitle("MIX-001");
        link(colTitleId, real);
        link(colTitleId, sentinel);

        JavdbClient fakeClient = multiCastClient("MIX-001", "RealActress", "Various");
        EnrichmentRunner runner = collectionRunner(fakeClient);

        queue.enqueueTitle(EnrichmentJob.SOURCE_COLLECTION, colTitleId, null);
        runner.runOneStep();

        var jobs = drainAllProfileJobs();
        assertEquals(1, jobs.size(), "only the non-sentinel cast member should chain");
        assertEquals(real, jobs.get(0).actressId());
    }

    /**
     * Regression for the single-cast fallback bug: when javdb returns only 1 cast entry but the DB
     * credits 2 actresses, the lone slug must NOT be stamped onto both. Only the name-matched one
     * (or none) gets it.
     */
    @Test
    void collectionJob_singleCastFallback_isDisabled() throws Exception {
        long alice = seedActress("Alice", false);  // matches the single cast entry by name
        long bob   = seedActress("Bob",   false);  // no name match
        seedActressCredits(alice, 2);
        seedActressCredits(bob,   2);
        long colTitleId = seedTitle("LONE-001");
        link(colTitleId, alice);
        link(colTitleId, bob);

        // javdb returns ONLY one cast entry ("Alice") even though DB has two actresses.
        JavdbClient fakeClient = multiCastClient("LONE-001", "Alice");
        EnrichmentRunner runner = collectionRunner(fakeClient);

        queue.enqueueTitle(EnrichmentJob.SOURCE_COLLECTION, colTitleId, null);
        runner.runOneStep();

        // Alice gets the slug (name match). Bob must NOT get it (single-cast fallback disabled).
        var aliceSlug = stagingRepo.findActressStaging(alice).map(JavdbActressStagingRow::javdbSlug).orElse(null);
        var bobSlug   = stagingRepo.findActressStaging(bob).map(JavdbActressStagingRow::javdbSlug).orElse(null);
        assertEquals("fk01", aliceSlug);
        assertNull(bobSlug, "Bob must not inherit the lone javdb slug — single-cast fallback off for collections");
    }

    // ── URGENT bypass / operator pause (Step 3) ───────────────────────────

    /**
     * Operator pause (setPaused=true) must block URGENT execution — the runner sleeps
     * at step 1 without ever calling claimNextUrgentJob.
     */
    @Test
    void runOneStep_operatorPause_blocksUrgentExecution() throws Exception {
        JavdbClient neverCalled = new JavdbClient() {
            @Override public String searchByCode(String code) { throw new AssertionError("should not be called when paused"); }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, neverCalled, new JavdbSlugResolver(neverCalled), extractor, projector,
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);
        runner.setPaused(true);

        // URGENT job in queue — but operator pause must prevent its execution.
        queue.enqueueTitle(EnrichmentJob.SOURCE_ACTRESS, 999L, 999L, Priority.URGENT);

        // runOneStep sleeps 30 s when paused; interrupt from a daemon thread after a short delay.
        Thread t = new Thread(() -> {
            try { runner.runOneStep(); } catch (InterruptedException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(150);
        t.interrupt();
        t.join(2_000);

        assertEquals(1, queue.countPending(), "operator pause must prevent URGENT job from being claimed");
    }

    /**
     * URGENT jobs bypass the rate-limit pauseUntil gate: after a 429 sets pauseUntil,
     * the next call to runOneStep still executes any URGENT jobs while NORMAL/HIGH remain blocked.
     */
    @Test
    void runOneStep_urgentJob_bypasses_pauseUntil() throws Exception {
        long normalTitleId = seedTitle("NRM-001");
        long urgentTitleId = seedTitle("URG-001");
        long actressId     = seedActress("UrgentTest", false);

        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        JavdbClient fakeClient = new JavdbClient() {
            @Override public String searchByCode(String code) {
                if (callCount.incrementAndGet() == 1) {
                    throw new JavdbRateLimitException("https://javdb.com/");
                }
                return "<html>no results</html>"; // 2nd call: CodeNotFound → not_found
            }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, fakeClient, new JavdbSlugResolver(fakeClient), extractor, projector,
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        // Step 1: 429 on NORMAL job → sets pauseUntil
        queue.enqueueTitle(EnrichmentJob.SOURCE_ACTRESS, normalTitleId, actressId, Priority.NORMAL);
        runner.runOneStep();
        assertTrue(java.time.Instant.now().isBefore(runner.getPauseUntil()), "pauseUntil should be active after 429");

        // Step 2: URGENT enqueued — must bypass the active pauseUntil
        queue.enqueueTitle(EnrichmentJob.SOURCE_ACTRESS, urgentTitleId, actressId, Priority.URGENT);
        runner.runOneStep();

        // URGENT job was executed (permanently failed as "not_found")
        String urgentStatus = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM javdb_enrichment_queue WHERE target_id = :id")
                .bind("id", urgentTitleId).mapTo(String.class).one());
        assertEquals("failed", urgentStatus, "URGENT job must execute even when pauseUntil is active");

        // NORMAL job is still pending — not claimed because it was blocked by pauseUntil
        String normalStatus = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM javdb_enrichment_queue WHERE target_id = :id")
                .bind("id", normalTitleId).mapTo(String.class).one());
        assertEquals("pending", normalStatus, "NORMAL job must remain pending while pauseUntil is active");
    }

    /**
     * A 429 received while executing an URGENT job must still set pauseUntil (honest accounting),
     * but the job is released back to pending for retry.
     */
    @Test
    void runOneStep_urgentJob_on429_setsPauseUntilAndReleasesToRetry() throws Exception {
        long titleId   = seedTitle("URG-002");
        long actressId = seedActress("UrgentTest2", false);

        JavdbClient rateLimitClient = new JavdbClient() {
            @Override public String searchByCode(String code) { throw new JavdbRateLimitException("https://javdb.com/"); }
            @Override public String fetchTitlePage(String slug) { throw new AssertionError("not expected"); }
            @Override public String fetchActressPage(String slug) { throw new AssertionError("not expected"); }
        };
        EnrichmentRunner runner = new EnrichmentRunner(
                CONFIG, rateLimitClient, new JavdbSlugResolver(rateLimitClient), extractor, projector,
                stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, null, null, null, null, jdbi, null, null);

        queue.enqueueTitle(EnrichmentJob.SOURCE_ACTRESS, titleId, actressId, Priority.URGENT);
        runner.runOneStep();

        assertTrue(java.time.Instant.now().isBefore(runner.getPauseUntil()),
                "URGENT 429 must set pauseUntil (burst counter remains honest)");
        assertEquals(1, queue.countPending(), "URGENT job must be released to pending for retry after 429");
    }

    // ── Helpers for the tests above ────────────────────────────────────────

    private long seedActress(String name, boolean sentinel) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses (canonical_name, stage_name, tier, first_seen_at, is_sentinel) " +
                "VALUES (:n, :n, 'LIBRARY', '2024-01-01', :s)")
                .bind("n", name)
                .bind("s", sentinel ? 1 : 0)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long seedTitle(String code) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles (code, base_code, label, seq_num) VALUES (:c, :c, 'X', 1)")
                .bind("c", code)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private void link(long titleId, long actressId) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses (actress_id, title_id) VALUES (?, ?)", actressId, titleId));
    }

    private void seedActressCredits(long actressId, int count) {
        for (int i = 1; i <= count; i++) {
            String code = "PAD-" + actressId + "-" + i;
            long t = seedTitle(code);
            link(t, actressId);
        }
    }

    private JavdbClient multiCastClient(String expectedCode, String... castNames) {
        String slug = "lone1";
        StringBuilder html = new StringBuilder("""
                <html><body>
                  <div class="movie-panel-info">
                    <div class="panel-block"><strong>Release Date:</strong><span>2024-01-01</span></div>
                    <div class="panel-block"><strong>Actors:</strong>
                      <span class="value">
                """);
        int i = 0;
        for (String name : castNames) {
            html.append("<a href=\"/actors/fk0").append(++i).append("\">").append(name).append("</a> ");
        }
        html.append("</span></div></div></body></html>");
        String search = "<a href=\"/v/" + slug + "\">" + expectedCode + "</a>";
        String titleHtml = html.toString();
        return new JavdbClient() {
            @Override public String searchByCode(String code) { return search; }
            @Override public String fetchTitlePage(String s) { return titleHtml; }
            @Override public String fetchActressPage(String s) { throw new AssertionError("not expected"); }
        };
    }

    private EnrichmentRunner collectionRunner(JavdbClient client) {
        ProfileChainGate gate = new ProfileChainGate(jdbi, CONFIG);
        var titleActressRepo = new com.organizer3.repository.jdbi.JdbiTitleActressRepository(jdbi);
        return new EnrichmentRunner(
                CONFIG, client, new JavdbSlugResolver(client), extractor, projector, stagingRepo, enrichmentRepo, queue, titleRepo, actressRepo,
                new AutoPromoter(jdbi), null, null, null, gate, titleActressRepo, null, null, jdbi, null, null);
    }

    private java.util.List<EnrichmentJob> drainAllProfileJobs() {
        java.util.List<EnrichmentJob> jobs = new java.util.ArrayList<>();
        while (true) {
            Optional<EnrichmentJob> j = queue.claimNextJob();
            if (j.isEmpty()) break;
            if (EnrichmentJob.FETCH_ACTRESS_PROFILE.equals(j.get().jobType())) {
                jobs.add(j.get());
            }
        }
        return jobs;
    }

    /**
     * Builds a minimal fake javdb title page HTML with a single cast member.
     * The name is used verbatim so that {@code matchAndRecordActressSlug} can match it.
     */
    private String buildFakeTitleHtml(String actressName) {
        return """
                <html><body>
                  <div class="movie-panel-info">
                    <div class="panel-block"><strong>Release Date:</strong><span>2024-01-01</span></div>
                    <div class="panel-block"><strong>Actors:</strong>
                      <span class="value">
                        <a href="/actors/fk01">""" + actressName + """
                </a></span></div></div></body></html>""";
    }

    private String loadFixture(String name) throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("javdb/" + name);
        assertNotNull(url, "fixture not found: " + name);
        return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
    }
}
