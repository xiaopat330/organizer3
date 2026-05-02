package com.organizer3.javdb.draft;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DraftPromotionService}.
 *
 * <p>Uses real in-memory SQLite via {@link SchemaInitializer}. Tests seed draft rows
 * using Phase 1 repos and verify canonical state via SQL after promotion.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §4, §5.1, §6, §9.3, §13.
 */
class DraftPromotionServiceTest {

    @TempDir
    Path dataDir;

    private Connection connection;
    private Jdbi jdbi;

    // ── Repos ─────────────────────────────────────────────────────────────────
    private DraftTitleRepository          draftTitleRepo;
    private DraftActressRepository        draftActressRepo;
    private DraftTitleActressesRepository draftCastRepo;
    private DraftTitleEnrichmentRepository draftEnrichRepo;
    private DraftCoverScratchStore        coverStore;

    // ── Service ───────────────────────────────────────────────────────────────
    private DraftPromotionService service;
    private TitleRepository       titleRepo;
    private CoverPath             coverPath;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        // Seed canonical actors/titles
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1,'TST-00001','TST-00001','TST',1)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2,'TST-00002','TST-00002','TST',2)");
            // Sentinel actress
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) VALUES (99,'Amateur',10,'2024-01-01',1)");
            // Existing actress
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (10,'Mana Sakura',1,'2024-01-01')");
            // Curated tag must exist before enrichment_tag_definitions references it (FK)
            h.execute("INSERT OR IGNORE INTO tags(name, category) VALUES ('big-tits', 'body')");
            // Tag definitions: 'big tits' has curated_alias → 'big-tits'; 'solo' has no alias
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions(name, curated_alias, title_count) VALUES ('big tits', 'big-tits', 0)");
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions(name, title_count) VALUES ('solo', 0)");
        });

        draftTitleRepo  = new DraftTitleRepository(jdbi);
        draftActressRepo = new DraftActressRepository(jdbi);
        draftCastRepo   = new DraftTitleActressesRepository(jdbi);
        draftEnrichRepo = new DraftTitleEnrichmentRepository(jdbi);
        coverStore      = new DraftCoverScratchStore(dataDir);
        coverPath       = new CoverPath(dataDir);
        titleRepo       = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));

        EnrichmentHistoryRepository historyRepo = new EnrichmentHistoryRepository(jdbi, JSON);
        TitleEffectiveTagsService effectiveTags = new TitleEffectiveTagsService(jdbi);
        CastValidator castValidator = new CastValidator();

        service = new DraftPromotionService(
                jdbi, draftTitleRepo, draftActressRepo, draftCastRepo,
                draftEnrichRepo, coverStore, coverPath, castValidator,
                titleRepo, historyRepo, effectiveTags, JSON);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long seedDraft(long titleId, String castJson, String resolution, String javdbSlug,
                            Long linkToExistingId) {
        return seedDraftFull(titleId, castJson, resolution, javdbSlug,
                linkToExistingId, null, null, "tags", "2024-06-01T00:00:00Z");
    }

    private long seedDraftFull(long titleId, String castJson, String resolution, String javdbSlug,
                                Long linkToExistingId, String englishFirst, String englishLast,
                                String tagsJson, String updatedAt) {
        DraftTitle dt = DraftTitle.builder()
                .titleId(titleId)
                .code("TST-" + titleId)
                .titleOriginal("Original " + titleId)
                .titleEnglish("English " + titleId)
                .releaseDate("2024-06-01")
                .notes(null)
                .grade("A")
                .gradeSource("enrichment")
                .upstreamChanged(false)
                .createdAt("2024-06-01T00:00:00Z")
                .updatedAt(updatedAt)
                .build();
        long draftId = draftTitleRepo.insert(dt);

        DraftEnrichment enrichment = DraftEnrichment.builder()
                .draftTitleId(draftId)
                .javdbSlug("slug-" + titleId)
                .castJson(castJson)
                .maker("TestMaker")
                .series("TestSeries")
                .coverUrl("http://example.com/cover.jpg")
                .tagsJson(tagsJson != null ? tagsJson : "[]")
                .ratingAvg(4.0)
                .ratingCount(100)
                .resolverSource("auto_enriched")
                .updatedAt(updatedAt)
                .build();
        draftEnrichRepo.upsert(draftId, enrichment);

        if (javdbSlug != null) {
            DraftActress da = DraftActress.builder()
                    .javdbSlug(javdbSlug)
                    .stageName("TestStage")
                    .englishFirstName(englishFirst)
                    .englishLastName(englishLast)
                    .linkToExistingId(linkToExistingId)
                    .createdAt("2024-06-01T00:00:00Z")
                    .updatedAt("2024-06-01T00:00:00Z")
                    .build();
            draftActressRepo.upsertBySlug(da);

            DraftTitleActress slot = new DraftTitleActress(draftId, javdbSlug, resolution);
            draftCastRepo.replaceForDraft(draftId, List.of(slot));
        }

        return draftId;
    }

    private String castJson(String... names) throws Exception {
        var entries = new java.util.ArrayList<Map<String, String>>();
        for (String name : names) {
            entries.add(Map.of("slug", "slug-" + name, "name", name, "gender", "F"));
        }
        return JSON.writeValueAsString(entries);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void happyPath_singlePickActress_promotesCorrectly() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);

        long titleId = service.promote(draftId, "2024-06-01T00:00:00Z");

        assertEquals(1L, titleId);

        // titles row updated
        jdbi.useHandle(h -> {
            var row = h.createQuery("SELECT * FROM titles WHERE id = 1")
                    .mapToMap().one();
            assertEquals("Original 1", row.get("title_original"));
            assertEquals("English 1", row.get("title_english"));
        });

        // title_actresses contains the picked actress
        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id=1 AND actress_id=10")
                        .mapTo(Integer.class).one());
        assertEquals(1, count);

        // title_javdb_enrichment written
        int enrichCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE title_id=1")
                        .mapTo(Integer.class).one());
        assertEquals(1, enrichCount);

        // draft rows deleted
        assertTrue(draftTitleRepo.findById(draftId).isEmpty());
    }

    @Test
    void happyPath_createNewActress_insertsActressRow() throws Exception {
        long draftId = seedDraftFull(1L, castJson("New Actress"), "create_new", "slug-new",
                null, "Jane", "Doe", "[]", "2024-06-01T00:00:00Z");

        long titleId = service.promote(draftId, "2024-06-01T00:00:00Z");

        assertEquals(1L, titleId);

        // New actress created
        int actressCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE canonical_name='Jane Doe'")
                        .mapTo(Integer.class).one());
        assertEquals(1, actressCount);

        // created_via = 'draft_promotion'
        String createdVia = jdbi.withHandle(h ->
                h.createQuery("SELECT created_via FROM actresses WHERE canonical_name='Jane Doe'")
                        .mapTo(String.class).one());
        assertEquals("draft_promotion", createdVia);

        // title_actresses linked
        int linkCount = jdbi.withHandle(h -> {
            Long newId = h.createQuery("SELECT id FROM actresses WHERE canonical_name='Jane Doe'")
                    .mapTo(Long.class).one();
            return h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id=1 AND actress_id=:id")
                    .bind("id", newId)
                    .mapTo(Integer.class).one();
        });
        assertEquals(1, linkCount);
    }

    @Test
    void happyPath_skipActress_notInTitleActresses() throws Exception {
        // 2 javdb stage_names, 1 pick + 1 skip
        String cast = castJson("Mana Sakura", "Unknown One");
        long draftId = seedDraftFull(1L, cast, "pick", "slug-mana", 10L,
                null, null, "[]", "2024-06-01T00:00:00Z");
        // Add skip slot
        draftActressRepo.upsertBySlug(DraftActress.builder()
                .javdbSlug("slug-unknown-one")
                .stageName("Unknown One")
                .createdAt("2024-06-01T00:00:00Z")
                .updatedAt("2024-06-01T00:00:00Z")
                .build());
        draftCastRepo.replaceForDraft(draftId, List.of(
                new DraftTitleActress(draftId, "slug-mana", "pick"),
                new DraftTitleActress(draftId, "slug-unknown-one", "skip")));

        service.promote(draftId, "2024-06-01T00:00:00Z");

        // Only 1 actress in title_actresses (the pick, not the skip)
        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id=1")
                        .mapTo(Integer.class).one());
        assertEquals(1, count);
    }

    @Test
    void happyPath_sentinelMode_writesCorrectly() throws Exception {
        // 0 javdb stage_names → sentinel-only mode
        long draftId = seedDraftFull(1L, "[]", "sentinel:99", "sentinel-slot-slug",
                null, null, null, "[]", "2024-06-01T00:00:00Z");

        service.promote(draftId, "2024-06-01T00:00:00Z");

        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id=1 AND actress_id=99")
                        .mapTo(Integer.class).one());
        assertEquals(1, count);
    }

    // ── Tag resolution (§6) ───────────────────────────────────────────────────

    @Test
    void tagResolution_aliasApplied_effectiveTagsContainAliasedTag() throws Exception {
        // 'big tits' has curated_alias = 'big-tits'
        String tagsJson = JSON.writeValueAsString(List.of("big tits", "solo"));
        long draftId = seedDraftFull(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L,
                null, null, tagsJson, "2024-06-01T00:00:00Z");

        service.promote(draftId, "2024-06-01T00:00:00Z");

        // title_enrichment_tags has both raw tags
        int tagCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_enrichment_tags WHERE title_id=1")
                        .mapTo(Integer.class).one());
        assertEquals(2, tagCount, "Both raw tags should be in title_enrichment_tags");

        // title_effective_tags contains the aliased tag 'big-tits' (via curated_alias)
        int effectiveCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_effective_tags WHERE title_id=1 AND tag='big-tits'")
                        .mapTo(Integer.class).one());
        assertEquals(1, effectiveCount, "Aliased tag should appear in title_effective_tags");
    }

    @Test
    void tagResolution_rawTagWithoutAlias_stillWritten() throws Exception {
        String tagsJson = JSON.writeValueAsString(List.of("solo"));
        long draftId = seedDraftFull(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L,
                null, null, tagsJson, "2024-06-01T00:00:00Z");

        service.promote(draftId, "2024-06-01T00:00:00Z");

        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_enrichment_tags WHERE title_id=1")
                        .mapTo(Integer.class).one());
        assertEquals(1, count);
    }

    // ── Audit log (§9.3) ──────────────────────────────────────────────────────

    @Test
    void auditLog_writtenWithPromotionMetadata() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);

        service.promote(draftId, "2024-06-01T00:00:00Z");

        int histCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment_history WHERE title_id=1")
                        .mapTo(Integer.class).one());
        assertEquals(1, histCount, "One audit log row should be written on promotion");

        // Verify promotion_metadata is written
        String metadata = jdbi.withHandle(h ->
                h.createQuery("SELECT promotion_metadata FROM title_javdb_enrichment_history WHERE title_id=1")
                        .mapTo(String.class).one());
        assertNotNull(metadata, "promotion_metadata must not be null");
        Map<String, Object> meta = JSON.readValue(metadata, new TypeReference<>() {});
        assertNotNull(meta.get("resolutions"));
        assertNotNull(meta.get("skip_count"));
        assertNotNull(meta.get("cover_fetched"));

        // new_payload should be populated
        String newPayload = jdbi.withHandle(h ->
                h.createQuery("SELECT new_payload FROM title_javdb_enrichment_history WHERE title_id=1")
                        .mapTo(String.class).one());
        assertNotNull(newPayload, "new_payload must be populated");
    }

    @Test
    void auditLog_priorPayloadNull_whenNoPriorEnrichment() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);

        service.promote(draftId, "2024-06-01T00:00:00Z");

        String priorPayload = jdbi.withHandle(h ->
                h.createQuery("SELECT prior_payload FROM title_javdb_enrichment_history WHERE title_id=1")
                        .mapTo(String.class).one());
        // No prior enrichment exists so prior_payload should be null
        assertNull(priorPayload, "prior_payload should be null when no prior enrichment existed");
    }

    // ── Rollback: pre-flight failure ──────────────────────────────────────────

    @Test
    void preflightFailure_leavesAllStateUntouched() throws Exception {
        // Create a draft with UNRESOLVED cast slot
        long draftId = seedDraftFull(1L, castJson("Mana Sakura"), "unresolved", "slug-mana", null,
                null, null, "[]", "2024-06-01T00:00:00Z");

        assertThrows(PreFlightFailedException.class,
                () -> service.promote(draftId, "2024-06-01T00:00:00Z"));

        // titles NOT updated
        String titleOriginal = jdbi.withHandle(h ->
                h.createQuery("SELECT title_original FROM titles WHERE id=1")
                        .mapTo(String.class).one());
        assertNull(titleOriginal, "titles should not be updated on preflight failure");

        // draft intact
        assertTrue(draftTitleRepo.findById(draftId).isPresent(), "draft should still exist");

        // no enrichment written
        int enrichCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE title_id=1")
                        .mapTo(Integer.class).one());
        assertEquals(0, enrichCount);
    }

    // ── Rollback: optimistic lock conflict ────────────────────────────────────

    @Test
    void optimisticLockConflict_throws() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);

        assertThrows(OptimisticLockException.class,
                () -> service.promote(draftId, "wrong-token"));

        // draft intact
        assertTrue(draftTitleRepo.findById(draftId).isPresent());
    }

    // ── Rollback: upstream_changed rejection ──────────────────────────────────

    @Test
    void upstreamChanged_rejectsPromotion() throws Exception {
        DraftTitle dt = DraftTitle.builder()
                .titleId(1L)
                .code("TST-1")
                .titleOriginal("Original")
                .upstreamChanged(true)
                .createdAt("2024-06-01T00:00:00Z")
                .updatedAt("2024-06-01T00:00:00Z")
                .build();
        long draftId = draftTitleRepo.insert(dt);
        draftEnrichRepo.upsert(draftId, DraftEnrichment.builder()
                .draftTitleId(draftId).javdbSlug("s").castJson("[]").tagsJson("[]")
                .resolverSource("auto_enriched").updatedAt("2024-06-01T00:00:00Z").build());

        PreFlightResult result = service.preflight(draftId, null);
        assertFalse(result.ok());
        assertTrue(result.errors().contains("UPSTREAM_CHANGED"));
    }

    // ── Rollback: cover copy failure ──────────────────────────────────────────

    @Test
    void coverCopyFailure_rollsBackDbAndCleansPartialFile() throws Exception {
        // Write a scratch cover file
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);
        coverStore.write(draftId, new byte[]{1, 2, 3});

        // Inject a failing copier
        service.setCoverCopier((src, dst) -> {
            // Write partial bytes to simulate a partial copy
            try { Files.write(dst, new byte[]{1}); } catch (IOException ignored) {}
            throw new IOException("Simulated disk failure");
        });

        assertThrows(PromotionException.class,
                () -> service.promote(draftId, "2024-06-01T00:00:00Z"));

        // titles NOT updated
        String titleOriginal = jdbi.withHandle(h ->
                h.createQuery("SELECT title_original FROM titles WHERE id=1")
                        .mapTo(String.class).one());
        assertNull(titleOriginal, "titles should not be updated when cover copy fails");

        // draft intact
        assertTrue(draftTitleRepo.findById(draftId).isPresent());

        // Partial copy at dest should have been cleaned up by the service
        // (the code deletes dest on IOException before rethrowing)
        Title t = titleRepo.findById(1L).orElseThrow();
        Path dest = coverPath.resolve(t, "jpg");
        assertFalse(Files.exists(dest), "Partial destination cover should be cleaned up");
    }

    // ── Rollback: mid-transaction failure ─────────────────────────────────────

    @Test
    void rollbackOnMidTransactionFailure_restoresAllState() throws Exception {
        // Seed a draft where the enrichment row's tags_json is malformed to cause
        // a mid-transaction failure AFTER the titles UPDATE but BEFORE the transaction commits.
        // We force this by manually inserting draft_actress with a valid actress but then
        // pointing the enrichment to a javdb_slug that will fail the enrichment write.
        //
        // Actually: we can test this by using a create_new actress whose english_last_name
        // points to a duplicate canonical_name (Mana Sakura already exists in actresses table).
        // INSERT INTO actresses will fail with UNIQUE constraint → rollback.
        long draftId = seedDraftFull(1L, castJson("Mana Sakura duplicate"),
                "create_new", "slug-dup", null, null, "Sakura" /* last name = 'Sakura' */,
                "[]", "2024-06-01T00:00:00Z");

        // Also set first name to "Mana" so canonical_name = "Mana Sakura" which already exists
        draftActressRepo.upsertBySlug(DraftActress.builder()
                .javdbSlug("slug-dup")
                .stageName("Mana Sakura duplicate")
                .englishFirstName("Mana")
                .englishLastName("Sakura")
                .createdAt("2024-06-01T00:00:00Z")
                .updatedAt("2024-06-01T00:00:00Z")
                .build());

        // promotion will try to INSERT INTO actresses with canonical_name='Mana Sakura'
        // which already exists → UNIQUE constraint violation → rollback
        assertThrows(Exception.class,
                () -> service.promote(draftId, "2024-06-01T00:00:00Z"));

        // titles NOT updated
        String titleOriginal = jdbi.withHandle(h ->
                h.createQuery("SELECT title_original FROM titles WHERE id=1")
                        .mapTo(String.class).one());
        assertNull(titleOriginal, "titles should not be updated on mid-txn failure");

        // draft intact
        assertTrue(draftTitleRepo.findById(draftId).isPresent());
    }

    // ── Cover copy: scratch present + no base cover → copy happens ────────────

    @Test
    void coverCopy_scratchPresentNoCover_copiesToCoverPath() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);
        coverStore.write(draftId, new byte[]{42, 43, 44});

        service.promote(draftId, "2024-06-01T00:00:00Z");

        Title t = titleRepo.findById(1L).orElseThrow();
        assertTrue(coverPath.exists(t), "Cover should have been copied to the cover path");
    }

    @Test
    void coverCopy_scratchPresentExistingCover_preservesExisting() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);
        coverStore.write(draftId, new byte[]{42, 43, 44});

        // Pre-create a cover at the destination so "no cover at base" is false
        Title t = titleRepo.findById(1L).orElseThrow();
        Path dest = coverPath.resolve(t, "jpg");
        Files.createDirectories(dest.getParent());
        Files.write(dest, new byte[]{1, 2, 3});  // existing cover

        service.promote(draftId, "2024-06-01T00:00:00Z");

        // Existing cover should still have original content
        byte[] content = Files.readAllBytes(dest);
        assertEquals(1, content[0], "Existing cover should not be overwritten");
    }

    @Test
    void coverCopy_scratchAbsent_noAttemptMade() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);
        // No scratch cover written

        service.promote(draftId, "2024-06-01T00:00:00Z");

        // No cover created
        Title t = titleRepo.findById(1L).orElseThrow();
        assertFalse(coverPath.exists(t), "No cover should exist when scratch was absent");
    }

    // ── Draft deleted after promotion ────────────────────────────────────────

    @Test
    void promotion_deletesDraftRows() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);

        service.promote(draftId, "2024-06-01T00:00:00Z");

        assertTrue(draftTitleRepo.findById(draftId).isEmpty(), "draft_titles row must be deleted");
        assertTrue(draftEnrichRepo.findByDraftId(draftId).isEmpty(), "draft enrichment row must be deleted");
        assertTrue(draftCastRepo.findByDraftTitleId(draftId).isEmpty(), "draft cast rows must be deleted");
    }

    // ── COMMIT-failure compensation ───────────────────────────────────────────

    /**
     * Verifies that when COMMIT fails (simulated via {@code preCommitHook}),
     * the cover that was already copied to its canonical destination is deleted
     * as compensation, so no orphaned cover file survives.
     *
     * <p>The {@code preCommitHook} is called inside the transaction lambda just
     * before {@code return canonicalTitleId}. Throwing there causes JDBI to
     * roll back the transaction (so all DB writes are reverted) AND causes the
     * outer {@code promote()} to enter the compensation catch block, which reads
     * {@code destCoverHolder[0]} and deletes the copied file.
     */
    @Test
    void commitFailureCompensation_deletesCopiedCover() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);
        coverStore.write(draftId, new byte[]{42, 43, 44});

        // Determine where the cover would be placed (before promote touches it)
        Title t = titleRepo.findById(1L).orElseThrow();
        Path expectedDest = coverPath.resolve(t, "jpg");
        assertFalse(Files.exists(expectedDest), "precondition: no cover at dest before promote");

        // Inject a hook that throws AFTER step 8 (cover copy) but before JDBI commits.
        // This simulates a COMMIT failure (disk-full, I/O error, etc.).
        service.setPreCommitHook(() -> {
            throw new RuntimeException("Simulated COMMIT failure");
        });

        // promote() must throw — COMMIT failure surfaces as an exception
        assertThrows(Exception.class,
                () -> service.promote(draftId, "2024-06-01T00:00:00Z"));

        // CRITICAL: the cover that was copied inside the lambda must have been deleted
        // by the compensation code in promote()'s catch block.
        assertFalse(Files.exists(expectedDest),
                "Compensation must delete the orphaned cover when COMMIT fails");
    }

    // ── Preflight: all checks ─────────────────────────────────────────────────

    @Test
    void preflight_missingDraft_throwsDraftNotFound() {
        assertThrows(DraftNotFoundException.class,
                () -> service.preflight(9999L, null));
    }

    @Test
    void preflight_mode1WithSentinel_returnsViolation() throws Exception {
        // 1 javdb stage_name, sentinel resolution → cast mode violation
        long draftId = seedDraftFull(1L, castJson("Mana Sakura"), "sentinel:99", "slot-slug",
                null, null, null, "[]", "2024-06-01T00:00:00Z");

        PreFlightResult result = service.preflight(draftId, null);
        assertFalse(result.ok());
        assertTrue(result.errors().contains("CAST_MODE_VIOLATION"));
    }

    @Test
    void preflight_createNewMissingLastName_returnsError() throws Exception {
        long draftId = seedDraftFull(1L, castJson("Unknown"), "create_new", "slug-unknown",
                null, null, null /* no last name */, "[]", "2024-06-01T00:00:00Z");

        PreFlightResult result = service.preflight(draftId, null);
        assertFalse(result.ok());
        assertTrue(result.errors().contains("MISSING_ENGLISH_LAST_NAME"));
    }

    @Test
    void preflight_unresolvedSlot_returnsError() throws Exception {
        long draftId = seedDraftFull(1L, castJson("Unknown"), "unresolved", "slug-unknown",
                null, null, null, "[]", "2024-06-01T00:00:00Z");

        PreFlightResult result = service.preflight(draftId, null);
        assertFalse(result.ok());
        assertTrue(result.errors().contains("UNRESOLVED_CAST_SLOT"));
    }

    @Test
    void preflight_optimisticLockMismatch_returnsError() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);

        PreFlightResult result = service.preflight(draftId, "wrong-token");
        assertFalse(result.ok());
        assertTrue(result.errors().contains("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    void preflight_validDraft_returnsOk() throws Exception {
        long draftId = seedDraft(1L, castJson("Mana Sakura"), "pick", "slug-mana", 10L);

        PreFlightResult result = service.preflight(draftId, "2024-06-01T00:00:00Z");
        assertTrue(result.ok());
        assertTrue(result.errors().isEmpty());
    }
}
