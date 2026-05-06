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
import com.organizer3.translation.repository.jdbi.JdbiStageNameSuggestionRepository;
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
    private DraftTitleRepository           draftTitleRepo;
    private DraftActressRepository         draftActressRepo;
    private DraftTitleActressesRepository  draftCastRepo;
    private DraftTitleEnrichmentRepository draftEnrichRepo;
    private DraftCoverScratchStore         coverStore;
    private JdbiStageNameSuggestionRepository suggestionRepo;

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
        suggestionRepo  = new JdbiStageNameSuggestionRepository(jdbi);

        EnrichmentHistoryRepository historyRepo = new EnrichmentHistoryRepository(jdbi, JSON);
        TitleEffectiveTagsService effectiveTags = new TitleEffectiveTagsService(jdbi);
        CastValidator castValidator = new CastValidator();

        service = new DraftPromotionService(
                jdbi, draftTitleRepo, draftActressRepo, draftCastRepo,
                draftEnrichRepo, coverStore, coverPath, castValidator,
                titleRepo, historyRepo, effectiveTags, JSON, suggestionRepo);
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

    // ── Sibling resolution + alias rebuild ────────────────────────────────────

    /** Inserts a draft_actress row with link_to_draft_slug via raw SQL (sibling pointer). */
    private void insertSiblingActress(String slug, String stageName,
                                      String firstName, String lastName,
                                      String linkToDraftSlug, String createdAt) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO draft_actresses
                    (javdb_slug, stage_name, english_first_name, english_last_name,
                     link_to_draft_slug, created_at, updated_at)
                VALUES (:slug, :stageName, :firstName, :lastName,
                        :linkToDraftSlug, :createdAt, :createdAt)
                """)
                .bind("slug",           slug)
                .bind("stageName",      stageName)
                .bind("firstName",      firstName)
                .bind("lastName",       lastName)
                .bind("linkToDraftSlug", linkToDraftSlug)
                .bind("createdAt",      createdAt)
                .execute());
    }

    @Test
    void sibling_primaryAlone_insertsSingleActress() throws Exception {
        long draftId = seedDraftFull(1L, castJson("Yuma Asami"), "create_new", "slug-yuma",
                null, "Yuma", "Asami", "[]", "2024-06-01T00:00:00Z");

        service.promote(draftId, "2024-06-01T00:00:00Z");

        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Integer.class).one());
        assertEquals(1, count, "exactly one actress must be created for a primary-alone slot");
    }

    @Test
    void sibling_primaryAndSiblingsInSameBatch_allResolveToPrimaryId() throws Exception {
        // Title 1: draft with 3 cast slots — slug-A (primary), slug-B (sibling→A), slug-C (sibling→A).
        // cast_json must have 3 entries to satisfy cast mode 1 (1+ javdb stage names + 1+ pick/create_new).
        String castJson3 = castJson("麻美ゆま-A", "麻美ゆま-B", "麻美ゆま-C");
        long draftId = jdbi.withHandle(h -> {
            h.execute("INSERT INTO draft_titles(title_id, code, title_original, title_english, release_date, grade, " +
                    "upstream_changed, created_at, updated_at) " +
                    "VALUES (1,'TST-1','Orig','Eng','2024-06-01','A',0,'2024-06-01T00:00:00Z','2024-06-01T00:00:00Z')");
            long id = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
            h.createUpdate("INSERT INTO draft_title_javdb_enrichment(draft_title_id,javdb_slug,cast_json,tags_json,resolver_source,updated_at) " +
                    "VALUES (:id,'slug-enrich',:castJson,'[]','auto_enriched','2024-06-01T00:00:00Z')")
                    .bind("id",id).bind("castJson", castJson3).execute();
            return id;
        });

        // Primary actress A
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO draft_actresses(javdb_slug,stage_name,english_first_name,english_last_name,created_at,updated_at) " +
                "VALUES ('slug-A','麻美ゆま','Yuma','Asami','2024-06-01T00:00:00Z','2024-06-01T00:00:00Z')"));
        // Sibling B → A
        insertSiblingActress("slug-B", "麻美ゆま", null, null, "slug-A", "2024-06-01T01:00:00Z");
        // Sibling C → A
        insertSiblingActress("slug-C", "麻美ゆま", null, null, "slug-A", "2024-06-01T02:00:00Z");

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO draft_title_actresses(draft_title_id,javdb_slug,resolution) VALUES ("+draftId+",'slug-A','create_new')");
            h.execute("INSERT INTO draft_title_actresses(draft_title_id,javdb_slug,resolution) VALUES ("+draftId+",'slug-B','create_new')");
            h.execute("INSERT INTO draft_title_actresses(draft_title_id,javdb_slug,resolution) VALUES ("+draftId+",'slug-C','create_new')");
        });

        service.promote(draftId, "2024-06-01T00:00:00Z");

        // Only one new actress should exist.
        int actressCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Integer.class).one());
        assertEquals(1, actressCount, "only one actress row for primary + siblings");

        // All three title_actresses entries (de-duped by IGNORE) point at that actress.
        Long actressId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Long.class).one());
        int titleActressCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id=1 AND actress_id=:id")
                        .bind("id", actressId).mapTo(Integer.class).one());
        // INSERT OR IGNORE deduplicates — one row per (title_id, actress_id)
        assertEquals(1, titleActressCount, "all siblings collapse to one title_actresses row");
    }

    @Test
    void sibling_orphanReelection_oldestBecomesNewPrimary() throws Exception {
        // Title 1: slug-B (sibling→X) and slug-C (sibling→X) where X is NOT in the batch.
        // cast_json must have 2 entries to satisfy cast mode 1.
        String castJson2 = castJson("麻美ゆま-B", "麻美ゆま-C");
        long draftId = jdbi.withHandle(h -> {
            h.execute("INSERT INTO draft_titles(title_id, code, title_original, title_english, release_date, grade, " +
                    "upstream_changed, created_at, updated_at) " +
                    "VALUES (1,'TST-1','Orig','Eng','2024-06-01','A',0,'2024-06-01T00:00:00Z','2024-06-01T00:00:00Z')");
            long id = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
            h.createUpdate("INSERT INTO draft_title_javdb_enrichment(draft_title_id,javdb_slug,cast_json,tags_json,resolver_source,updated_at) " +
                    "VALUES (:id,'slug-enrich',:castJson,'[]','auto_enriched','2024-06-01T00:00:00Z')")
                    .bind("id",id).bind("castJson", castJson2).execute();
            return id;
        });

        // slug-X exists in draft_actresses (it's a primary in a different draft title, not being promoted now).
        // B and C are siblings of X, but X is not in this batch → orphan re-election fires.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO draft_actresses(javdb_slug,stage_name,english_first_name,english_last_name,created_at,updated_at) " +
                "VALUES ('slug-X','麻美ゆま','Yuma','Asami','2024-06-01T00:00:00Z','2024-06-01T00:00:00Z')"));
        // B is older (created_at earlier), C is newer — B should be elected primary.
        insertSiblingActress("slug-B", "麻美ゆま", "Yuma", "Asami", "slug-X", "2024-06-01T01:00:00Z");
        insertSiblingActress("slug-C", "麻美ゆま", null, null,       "slug-X", "2024-06-01T02:00:00Z");

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO draft_title_actresses(draft_title_id,javdb_slug,resolution) VALUES ("+draftId+",'slug-B','create_new')");
            h.execute("INSERT INTO draft_title_actresses(draft_title_id,javdb_slug,resolution) VALUES ("+draftId+",'slug-C','create_new')");
        });

        service.promote(draftId, "2024-06-01T00:00:00Z");

        // One actress created.
        int actressCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Integer.class).one());
        assertEquals(1, actressCount, "one actress elected from orphan siblings");

        Long idB = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Long.class).one());

        // Both title_actress entries point to the same id (deduplicated by INSERT OR IGNORE).
        int taCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id=1 AND actress_id=:id")
                        .bind("id", idB).mapTo(Integer.class).one());
        assertEquals(1, taCount, "orphan siblings both resolve to re-elected primary");

        // slug-C's link_to_draft_slug was rewritten from slug-X to slug-B by re-election,
        // so future orphan siblings sharing the dead slug-X don't re-elect again.
        String rewrittenC = jdbi.withHandle(h ->
                h.createQuery("SELECT link_to_draft_slug FROM draft_actresses WHERE javdb_slug='slug-C'")
                        .mapTo(String.class).findOne().orElse(null));
        assertEquals("slug-B", rewrittenC, "orphan sibling link_to_draft_slug must be rewritten to elected primary");

        // Elected primary slug-B must have link_to_draft_slug = NULL (not pointing at itself or slug-X).
        String electedLink = jdbi.withHandle(h ->
                h.createQuery("SELECT link_to_draft_slug FROM draft_actresses WHERE javdb_slug='slug-B'")
                        .mapTo(String.class).findOne().orElse(null));
        assertNull(electedLink, "elected primary must have link_to_draft_slug = NULL");
    }

    @Test
    void aliasRebuild_kanjiAndLlmAndUserEditAllAgree_onlyKanjiAlias() throws Exception {
        // LLM romaji and user edit both say 'Yuma Asami' → canonical_name = 'Yuma Asami'.
        // Only the kanji alias differs → exactly 1 alias row.
        suggestionRepo.recordSuggestion("麻美ゆま", "Yuma Asami", "2024-06-01T00:00:00Z");

        long draftId = seedDraftFull(1L, castJson("麻美ゆま"), "create_new", "slug-yuma",
                null, "Yuma", "Asami", "[]", "2024-06-01T00:00:00Z");
        // Override stage_name on the draft_actress to the kanji form
        jdbi.useHandle(h -> h.execute("UPDATE draft_actresses SET stage_name='麻美ゆま' WHERE javdb_slug='slug-yuma'"));

        service.promote(draftId, "2024-06-01T00:00:00Z");

        Long actressId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Long.class).one());
        List<String> aliases = jdbi.withHandle(h ->
                h.createQuery("SELECT alias_name FROM actress_aliases WHERE actress_id=:id ORDER BY alias_name")
                        .bind("id", actressId)
                        .mapTo(String.class).list());
        assertEquals(List.of("麻美ゆま"), aliases,
                "when LLM and user edit both equal canonical, only kanji alias is written");
    }

    @Test
    void aliasRebuild_distinctUserEdit_includesBothKanjiAndLlmAlias() throws Exception {
        // LLM says 'Foo Bar', user edits to 'Yuma Asami'. Canonical = 'Yuma Asami'.
        // Aliases should include kanji + 'Foo Bar'.
        suggestionRepo.recordSuggestion("麻美ゆま", "Foo Bar", "2024-06-01T00:00:00Z");

        long draftId = seedDraftFull(1L, castJson("麻美ゆま"), "create_new", "slug-yuma",
                null, "Yuma", "Asami", "[]", "2024-06-01T00:00:00Z");
        jdbi.useHandle(h -> h.execute("UPDATE draft_actresses SET stage_name='麻美ゆま' WHERE javdb_slug='slug-yuma'"));

        service.promote(draftId, "2024-06-01T00:00:00Z");

        Long actressId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Long.class).one());
        List<String> aliases = jdbi.withHandle(h ->
                h.createQuery("SELECT alias_name FROM actress_aliases WHERE actress_id=:id ORDER BY alias_name")
                        .bind("id", actressId)
                        .mapTo(String.class).list());
        assertTrue(aliases.contains("麻美ゆま"), "kanji alias must be present");
        assertTrue(aliases.contains("Foo Bar"), "LLM romaji alias must be present when distinct from canonical");
        assertEquals(2, aliases.size(), "exactly kanji + LLM alias when user edit equals canonical");
    }

    @Test
    void aliasRebuild_noSuggestionRow_onlyKanjiAndComposedIfDistinct() throws Exception {
        // No suggestion row — alias rebuild produces kanji (+ composed if it differs from canonical).
        // Canonical = 'Yuma Asami', composed = 'Yuma Asami' → only kanji alias.
        long draftId = seedDraftFull(1L, castJson("麻美ゆま"), "create_new", "slug-yuma",
                null, "Yuma", "Asami", "[]", "2024-06-01T00:00:00Z");
        jdbi.useHandle(h -> h.execute("UPDATE draft_actresses SET stage_name='麻美ゆま' WHERE javdb_slug='slug-yuma'"));

        service.promote(draftId, "2024-06-01T00:00:00Z");

        Long actressId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Long.class).one());
        List<String> aliases = jdbi.withHandle(h ->
                h.createQuery("SELECT alias_name FROM actress_aliases WHERE actress_id=:id")
                        .bind("id", actressId)
                        .mapTo(String.class).list());
        assertEquals(List.of("麻美ゆま"), aliases,
                "without suggestion, only kanji alias when composed equals canonical");
    }

    @Test
    void aliasRebuild_mononym_onlyKanjiAlias() throws Exception {
        // Mononym: english_first_name IS NULL, english_last_name = 'Tama'.
        // Canonical = 'Tama'; composed = 'Tama' → only kanji alias.
        long draftId = seedDraftFull(1L, castJson("タマ"), "create_new", "slug-tama",
                null, null, "Tama", "[]", "2024-06-01T00:00:00Z");
        jdbi.useHandle(h -> h.execute("UPDATE draft_actresses SET stage_name='タマ' WHERE javdb_slug='slug-tama'"));

        service.promote(draftId, "2024-06-01T00:00:00Z");

        Long actressId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name='Tama'")
                        .mapTo(Long.class).one());
        List<String> aliases = jdbi.withHandle(h ->
                h.createQuery("SELECT alias_name FROM actress_aliases WHERE actress_id=:id")
                        .bind("id", actressId)
                        .mapTo(String.class).list());
        assertEquals(List.of("タマ"), aliases,
                "mononym: composed equals canonical, so only kanji alias is written");
    }

    @Test
    void aliasRebuild_idempotent_noDuplicatesOnDoubleInsert() throws Exception {
        // Seed a suggestion and promote. Then manually re-insert aliases with INSERT OR IGNORE —
        // verify no error and no duplicate rows.
        suggestionRepo.recordSuggestion("麻美ゆま", "Foo Bar", "2024-06-01T00:00:00Z");

        long draftId = seedDraftFull(1L, castJson("麻美ゆま"), "create_new", "slug-yuma",
                null, "Yuma", "Asami", "[]", "2024-06-01T00:00:00Z");
        jdbi.useHandle(h -> h.execute("UPDATE draft_actresses SET stage_name='麻美ゆま' WHERE javdb_slug='slug-yuma'"));

        service.promote(draftId, "2024-06-01T00:00:00Z");

        Long actressId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name='Yuma Asami'")
                        .mapTo(Long.class).one());

        // Re-insert via INSERT OR IGNORE — must not throw and must not create duplicates.
        assertDoesNotThrow(() -> jdbi.useHandle(h -> {
            h.createUpdate("INSERT OR IGNORE INTO actress_aliases(actress_id, alias_name) VALUES (:id,'麻美ゆま')")
                    .bind("id", actressId).execute();
            h.createUpdate("INSERT OR IGNORE INTO actress_aliases(actress_id, alias_name) VALUES (:id,'Foo Bar')")
                    .bind("id", actressId).execute();
        }));

        int aliasCount = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM actress_aliases WHERE actress_id=:id")
                        .bind("id", actressId).mapTo(Integer.class).one());
        assertEquals(2, aliasCount, "idempotent re-insert must not create duplicate alias rows");
    }
}
