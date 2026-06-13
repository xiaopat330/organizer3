package com.organizer3.javdb.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.enrichment.CastPresenceCheck;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.translation.repository.jdbi.JdbiStageNameSuggestionRepository;
import com.organizer3.web.CoverWriteService;
import com.organizer3.web.TitleFolderRenamer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the kanji-presence guard in {@link DraftPromotionService}.
 *
 * <p>Verifies that the guard correctly diverts (skips attribution + enqueues review)
 * when an actress's kanji name is absent from the title's cast_json, and correctly
 * attributes when present or when the guard is inactive (UNCHECKABLE, gated out).
 */
class DraftPromotionGuardTest {

    @TempDir
    Path dataDir;

    private Connection connection;
    private Jdbi jdbi;

    private DraftTitleRepository           draftTitleRepo;
    private DraftActressRepository         draftActressRepo;
    private DraftTitleActressesRepository  draftCastRepo;
    private DraftTitleEnrichmentRepository draftEnrichRepo;
    private DraftCoverScratchStore         coverStore;
    private JdbiStageNameSuggestionRepository suggestionRepo;
    private JavdbStagingRepository         javdbStagingRepo;
    private ActressRepository              actressRepo;
    private TitleRepository                titleRepo;
    private EnrichmentReviewQueueRepository reviewQueueRepo;
    private CoverWriteService              coverWriteService;
    private TitleFolderRenamer             renamer;
    private CastPresenceCheck              castPresenceCheck;
    private DraftPromotionService          service;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Actress seeded with kanji stage_name 桜花子. */
    private static final long ACTRESS_WITH_STAGE_NAME = 10L;
    /** Actress without kanji name. */
    private static final long ACTRESS_NO_KANJI = 11L;

    private static final String UPDATED_AT = "2024-06-01T00:00:00Z";

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1,'TST-00001','TST-00001','TST',1)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2,'TST-00002','TST-00002','TST',2)");
            // Actress with kanji stage_name
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, stage_name) "
                    + "VALUES (10,'Hanako Sakura','POPULAR','2024-01-01','桜花子')");
            // Actress without kanji
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) "
                    + "VALUES (11,'Romaji Only','LIBRARY','2024-01-01')");
        });

        draftTitleRepo   = new DraftTitleRepository(jdbi);
        draftActressRepo = new DraftActressRepository(jdbi);
        draftCastRepo    = new DraftTitleActressesRepository(jdbi);
        draftEnrichRepo  = new DraftTitleEnrichmentRepository(jdbi);
        coverStore       = new DraftCoverScratchStore(dataDir);
        titleRepo        = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        suggestionRepo   = new JdbiStageNameSuggestionRepository(jdbi);
        javdbStagingRepo = new JavdbStagingRepository(jdbi, JSON, dataDir);
        actressRepo      = new JdbiActressRepository(jdbi);
        reviewQueueRepo  = new EnrichmentReviewQueueRepository(jdbi);

        renamer = Mockito.mock(TitleFolderRenamer.class);
        when(renamer.renamePreservingDescriptor(anyLong(), anyList(), any()))
                .thenReturn(new TitleFolderRenamer.RenameOutcome(null, false));

        coverWriteService = Mockito.mock(CoverWriteService.class);
        castPresenceCheck = new CastPresenceCheck(jdbi);

        service = buildService(castPresenceCheck, reviewQueueRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private DraftPromotionService buildService(CastPresenceCheck guard,
                                                EnrichmentReviewQueueRepository queue) {
        EnrichmentHistoryRepository historyRepo = new EnrichmentHistoryRepository(jdbi, JSON);
        TitleEffectiveTagsService effectiveTags = new TitleEffectiveTagsService(jdbi);
        CoverPath coverPath = new CoverPath(dataDir);
        return new DraftPromotionService(
                jdbi, draftTitleRepo, draftActressRepo, draftCastRepo,
                draftEnrichRepo, coverStore, coverPath, new CastValidator(),
                titleRepo, historyRepo, effectiveTags, JSON, suggestionRepo,
                javdbStagingRepo, actressRepo,
                "unsorted", renamer,
                coverWriteService,
                guard, queue,
                null);       // Task 2b: ageRecomputer not under test here
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a cast_json string with one female entry.
     */
    private String castJsonOne(String kanjiName) throws Exception {
        return JSON.writeValueAsString(List.of(
                Map.of("slug", "s1", "name", kanjiName, "gender", "F")));
    }

    /**
     * Seeds a minimal draft for the given title with one pick-resolution cast slot.
     *
     * @param titleId        canonical title id (must exist in titles table)
     * @param castJson       cast_json for the draft enrichment
     * @param linkToActress  the actress id to link (pick resolution)
     * @param via            resolvedVia value (may be null)
     * @return the created draft title id
     */
    private long seedDraft(long titleId, String castJson, long linkToActress, String via) {
        DraftTitle dt = DraftTitle.builder()
                .titleId(titleId)
                .code("TST-" + titleId)
                .titleOriginal("Original " + titleId)
                .titleEnglish("English " + titleId)
                .releaseDate("2024-06-01")
                .grade("A")
                .gradeSource("enrichment")
                .upstreamChanged(false)
                .createdAt(UPDATED_AT)
                .updatedAt(UPDATED_AT)
                .bookmarkOnPromote(false)
                .build();
        long draftId = draftTitleRepo.insert(dt);

        DraftActress da = DraftActress.builder()
                .javdbSlug("slug-test")
                .stageName("テスト")
                .linkToExistingId(linkToActress)
                .createdAt(UPDATED_AT)
                .updatedAt(UPDATED_AT)
                .build();
        draftActressRepo.upsertBySlug(da);

        draftCastRepo.replaceForDraft(draftId, List.of(
                new DraftTitleActress(draftId, "slug-test", "pick", via)));

        DraftEnrichment enrichment = DraftEnrichment.builder()
                .draftTitleId(draftId)
                .javdbSlug("javdb-slug")
                .castJson(castJson)
                .maker("TestMaker")
                .tagsJson("[]")
                .ratingAvg(7.0)
                .ratingCount(100)
                .resolverSource("auto_enriched")
                .updatedAt(UPDATED_AT)
                .build();
        draftEnrichRepo.upsert(draftId, enrichment);

        return draftId;
    }

    private boolean isAttributed(long titleId, long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id = :t AND actress_id = :a")
                        .bind("t", titleId).bind("a", actressId)
                        .mapTo(Integer.class).one()) > 0;
    }

    private int countReviewQueue(long titleId, String reason) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM enrichment_review_queue "
                        + "WHERE title_id = :t AND reason = :r AND resolved_at IS NULL")
                        .bind("t", titleId).bind("r", reason)
                        .mapTo(Integer.class).one());
    }

    // ── Guard tests ───────────────────────────────────────────────────────────

    /**
     * Guard ACTIVE: slug resolution + actress kanji absent from cast → DIVERT.
     * Actress should NOT be attributed; review queue entry should be created.
     */
    @Test
    void guard_diverts_when_actress_absent_slug_resolution() throws Exception {
        // cast_json has 葵めぐみ but NOT 桜花子
        String castJson = castJsonOne("葵めぐみ");
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "slug");

        service.promote(draftId, UPDATED_AT);

        assertFalse(isAttributed(1L, ACTRESS_WITH_STAGE_NAME),
                "actress should be diverted (not attributed) when kanji absent");
        assertEquals(1, countReviewQueue(1L, "guard_cast_mismatch"),
                "one guard_cast_mismatch entry expected in review queue");
    }

    /**
     * Guard ACTIVE: slug resolution + actress kanji PRESENT in cast → attribute normally.
     */
    @Test
    void guard_attributes_when_actress_present_slug_resolution() throws Exception {
        // cast_json contains 桜花子 which IS the actress's stage_name
        String castJson = castJsonOne("桜花子");
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "slug");

        service.promote(draftId, UPDATED_AT);

        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME),
                "actress should be attributed when kanji present");
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"),
                "no guard_cast_mismatch entries expected");
    }

    /**
     * Guard ACTIVE: null via (legacy resolution) + actress kanji absent → DIVERT.
     */
    @Test
    void guard_diverts_when_via_null_and_actress_absent() throws Exception {
        String castJson = castJsonOne("葵めぐみ");
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, null);

        service.promote(draftId, UPDATED_AT);

        assertFalse(isAttributed(1L, ACTRESS_WITH_STAGE_NAME));
        assertEquals(1, countReviewQueue(1L, "guard_cast_mismatch"));
    }

    /**
     * Guard INACTIVE: canonical resolution → attribute normally even when kanji absent.
     */
    @Test
    void guard_bypassed_for_canonical_resolution() throws Exception {
        String castJson = castJsonOne("葵めぐみ");  // not the actress's kanji name
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "canonical");

        service.promote(draftId, UPDATED_AT);

        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME),
                "canonical resolution should skip the guard");
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"));
    }

    /**
     * Guard INACTIVE: alias resolution → attribute normally even when kanji absent.
     */
    @Test
    void guard_bypassed_for_alias_resolution() throws Exception {
        String castJson = castJsonOne("葵めぐみ");
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "alias");

        service.promote(draftId, UPDATED_AT);

        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME));
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"));
    }

    /**
     * Guard INACTIVE: stage_name resolution → attribute normally even when kanji absent.
     */
    @Test
    void guard_bypassed_for_stage_name_resolution() throws Exception {
        String castJson = castJsonOne("葵めぐみ");
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "stage_name");

        service.promote(draftId, UPDATED_AT);

        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME));
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"));
    }

    /**
     * Guard INACTIVE: create_new resolution → attribute normally (no attribution ambiguity —
     * the actress was just created from this cast entry).
     */
    @Test
    void guard_bypassed_for_create_new_resolution() throws Exception {
        // Seed a draft with create_new resolution for a new actress
        DraftTitle dt = DraftTitle.builder()
                .titleId(2L).code("TST-2")
                .titleOriginal("New Actress Test").titleEnglish("New Actress Test")
                .releaseDate("2024-06-01").grade("A").gradeSource("enrichment")
                .upstreamChanged(false)
                .createdAt(UPDATED_AT).updatedAt(UPDATED_AT)
                .bookmarkOnPromote(false).build();
        long draftId = draftTitleRepo.insert(dt);

        DraftActress da = DraftActress.builder()
                .javdbSlug("slug-new-actress")
                .stageName("新しい人")
                .englishFirstName("New")
                .englishLastName("Actress")
                .createdAt(UPDATED_AT).updatedAt(UPDATED_AT)
                .build();
        draftActressRepo.upsertBySlug(da);

        String castJson = castJsonOne("別の人");  // kanji that doesn't match — but exempt
        draftCastRepo.replaceForDraft(draftId, List.of(
                new DraftTitleActress(draftId, "slug-new-actress", "create_new", null)));

        DraftEnrichment enrichment = DraftEnrichment.builder()
                .draftTitleId(draftId)
                .javdbSlug("javdb-slug-2")
                .castJson(castJson)
                .maker("TestMaker")
                .tagsJson("[]")
                .ratingAvg(7.0)
                .ratingCount(100)
                .resolverSource("auto_enriched")
                .updatedAt(UPDATED_AT)
                .build();
        draftEnrichRepo.upsert(draftId, enrichment);

        service.promote(draftId, UPDATED_AT);

        // New actress should have been created and attributed
        Long newActressId = jdbi.withHandle(h ->
                h.createQuery("SELECT id FROM actresses WHERE canonical_name = 'New Actress'")
                        .mapTo(Long.class).findOne().orElse(null));
        assertNotNull(newActressId, "new actress should be created");
        assertTrue(isAttributed(2L, newActressId), "new actress should be attributed via create_new");
        assertEquals(0, countReviewQueue(2L, "guard_cast_mismatch"),
                "create_new should not trigger guard");
    }

    /**
     * Guard INACTIVE: UNCHECKABLE (actress has no kanji name) → attribute normally, no divert.
     */
    @Test
    void guard_not_enforced_for_uncheckable_actress() throws Exception {
        String castJson = castJsonOne("誰か");
        // ACTRESS_NO_KANJI has no stage_name, no aliases
        long draftId = seedDraft(1L, castJson, ACTRESS_NO_KANJI, "slug");

        service.promote(draftId, UPDATED_AT);

        assertTrue(isAttributed(1L, ACTRESS_NO_KANJI),
                "actress without kanji name should be attributed (UNCHECKABLE)");
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"));
    }

    /**
     * Guard disabled (null) → attribute normally regardless of kanji match.
     */
    @Test
    void guard_disabled_when_null() throws Exception {
        DraftPromotionService serviceNoGuard = buildService(null, null);

        String castJson = castJsonOne("葵めぐみ");  // not the actress's kanji name
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "slug");

        serviceNoGuard.promote(draftId, UPDATED_AT);

        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME),
                "actress should be attributed when guard is disabled (null)");
    }

    /**
     * Guard ACTIVE: fuzzy resolution + actress kanji absent → DIVERT.
     */
    @Test
    void guard_diverts_for_fuzzy_resolution_and_absent() throws Exception {
        String castJson = castJsonOne("葵めぐみ");
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "fuzzy");

        service.promote(draftId, UPDATED_AT);

        assertFalse(isAttributed(1L, ACTRESS_WITH_STAGE_NAME));
        assertEquals(1, countReviewQueue(1L, "guard_cast_mismatch"));
    }

    /**
     * Guard INACTIVE: fuzzy resolution + actress kanji absent, but title is compilation-tagged
     * → attribute normally (guard gated out by comp tag), no divert.
     */
    @Test
    void guard_not_enforced_for_comp_tagged_title() throws Exception {
        // Seed a compilation tag definition and apply it to title 1.
        // enrichment_tag_definitions.curated_alias REFERENCES tags(name), so seed tags first.
        jdbi.useHandle(h -> {
            h.execute("INSERT OR IGNORE INTO tags(name, category) VALUES ('compilation','genre')");
            h.execute("INSERT OR IGNORE INTO enrichment_tag_definitions(name, curated_alias, title_count) VALUES ('compilation','compilation',0)");
            long tagId = h.createQuery("SELECT id FROM enrichment_tag_definitions WHERE curated_alias='compilation'")
                    .mapTo(Long.class).one();
            // title_enrichment_tags.title_id → title_javdb_enrichment(title_id) FK; seed that row first.
            // We use title id=1 which was seeded in setUp().
            h.execute("INSERT OR IGNORE INTO title_javdb_enrichment(title_id, javdb_slug, fetched_at) VALUES (1,'SLUG1','2024-01-01T00:00:00Z')");
            h.execute("INSERT OR IGNORE INTO title_enrichment_tags(title_id, tag_id) VALUES (1, " + tagId + ")");
        });
        String castJson = castJsonOne("葵めぐみ");  // actress not in cast but comp tag present
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "fuzzy");

        service.promote(draftId, UPDATED_AT);

        // Guard gated out → attribute normally
        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME),
                "comp-tagged title should attribute even when actress absent from cast_json");
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"),
                "no guard_cast_mismatch for comp-tagged title");
    }

    /**
     * Guard INACTIVE: fuzzy resolution + actress kanji absent, but cast has 4 females
     * → attribute normally (nfem > 3 gates out the guard), no divert.
     */
    @Test
    void guard_not_enforced_for_large_cast() throws Exception {
        // Build cast_json with 4 female entries (none matching 桜花子)
        String castJson = JSON.writeValueAsString(List.of(
                Map.of("slug", "s1", "name", "人A", "gender", "F"),
                Map.of("slug", "s2", "name", "人B", "gender", "F"),
                Map.of("slug", "s3", "name", "人C", "gender", "F"),
                Map.of("slug", "s4", "name", "人D", "gender", "F")));
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "fuzzy");

        service.promote(draftId, UPDATED_AT);

        // Guard gated out → attribute normally
        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME),
                "large cast (nfem=4) should attribute even when actress absent from cast_json");
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"),
                "no guard_cast_mismatch for large cast");
    }

    /**
     * Two slots on the same title both divert: only ONE queue row should appear
     * (the (title_id, reason) unique index silently drops the second INSERT OR IGNORE),
     * and BOTH slots should be withheld (not attributed).
     */
    @Test
    void two_diverted_slots_produce_one_queue_row_and_both_withheld() throws Exception {
        // Actress 12: a second actress also absent from the cast
        jdbi.useHandle(h ->
                h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, stage_name) "
                        + "VALUES (12,'Second Actress','POPULAR','2024-01-01','二番女優')"));

        // cast_json with neither 桜花子 nor 二番女優
        String castJson = JSON.writeValueAsString(List.of(
                Map.of("slug", "s1", "name", "他の人", "gender", "F")));

        // Seed two draft_actress entries with the same title
        DraftTitle dt = DraftTitle.builder()
                .titleId(2L).code("TST-2")
                .titleOriginal("Two Slot Test").titleEnglish("Two Slot Test")
                .releaseDate("2024-06-01").grade("A").gradeSource("enrichment")
                .upstreamChanged(false).createdAt(UPDATED_AT).updatedAt(UPDATED_AT)
                .bookmarkOnPromote(false).build();
        long draftId = draftTitleRepo.insert(dt);

        draftActressRepo.upsertBySlug(DraftActress.builder()
                .javdbSlug("slug-a").stageName("テストA")
                .linkToExistingId(ACTRESS_WITH_STAGE_NAME)
                .createdAt(UPDATED_AT).updatedAt(UPDATED_AT).build());
        draftActressRepo.upsertBySlug(DraftActress.builder()
                .javdbSlug("slug-b").stageName("テストB")
                .linkToExistingId(12L)
                .createdAt(UPDATED_AT).updatedAt(UPDATED_AT).build());

        draftCastRepo.replaceForDraft(draftId, List.of(
                new DraftTitleActress(draftId, "slug-a", "pick", "slug"),
                new DraftTitleActress(draftId, "slug-b", "pick", "slug")));

        DraftEnrichment enrichment = DraftEnrichment.builder()
                .draftTitleId(draftId).javdbSlug("javdb-slug-2").castJson(castJson)
                .maker("TestMaker").tagsJson("[]").ratingAvg(7.0).ratingCount(100)
                .resolverSource("auto_enriched").updatedAt(UPDATED_AT).build();
        draftEnrichRepo.upsert(draftId, enrichment);

        service.promote(draftId, UPDATED_AT);

        assertFalse(isAttributed(2L, ACTRESS_WITH_STAGE_NAME),
                "first diverted slot must NOT be attributed");
        assertFalse(isAttributed(2L, 12L),
                "second diverted slot must NOT be attributed");
        assertEquals(1, countReviewQueue(2L, "guard_cast_mismatch"),
                "exactly ONE guard_cast_mismatch row expected (second INSERT OR IGNORE is a no-op)");
    }

    /**
     * purgeStale must NOT age out guard_cast_mismatch rows, even when older than 7 days.
     */
    @Test
    void purgeStale_does_not_expire_guard_cast_mismatch_rows() {
        // Insert a guard_cast_mismatch row with a created_at in the past (9 days ago)
        String oldTimestamp = java.time.Instant.now()
                .minus(9, java.time.temporal.ChronoUnit.DAYS).toString();
        jdbi.useHandle(h ->
                h.execute("INSERT INTO enrichment_review_queue(title_id, slug, reason, resolver_source, created_at) "
                        + "VALUES (1, 'test-slug', 'guard_cast_mismatch', 'test', '" + oldTimestamp + "')"));

        com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository queueRepo =
                new com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository(jdbi);
        int purged = queueRepo.purgeStale();

        // The guard_cast_mismatch row must still be open
        assertEquals(1, countReviewQueue(1L, "guard_cast_mismatch"),
                "guard_cast_mismatch row must survive purgeStale()");
    }

    /**
     * Guard INACTIVE: manual resolution → attribute normally (guard bypassed).
     */
    @Test
    void guard_bypassed_for_manual_resolution() throws Exception {
        String castJson = castJsonOne("葵めぐみ");  // actress not in cast
        long draftId = seedDraft(1L, castJson, ACTRESS_WITH_STAGE_NAME, "manual");

        service.promote(draftId, UPDATED_AT);

        assertTrue(isAttributed(1L, ACTRESS_WITH_STAGE_NAME),
                "manual resolution should bypass the guard");
        assertEquals(0, countReviewQueue(1L, "guard_cast_mismatch"));
    }
}
