package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DraftPatchService}.
 *
 * <p>Uses an in-memory SQLite database to exercise the full persistence path.
 * Tests cover: happy path (pick, create_new, skip, sentinel, unresolved),
 * optimistic-lock conflict, DraftNotFoundException, and every invalid
 * resolution shape (one test per error code).
 */
class DraftPatchServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private DraftTitleRepository draftTitleRepo;
    private DraftActressRepository draftActressRepo;
    private DraftTitleActressesRepository draftTitleActressesRepo;
    private DraftPatchService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        // Seed required rows.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)");
            // A real actress for 'pick' tests. tier is NOT NULL.
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) VALUES (10, 'Mana Sakura', 'S', '2024-01-01')");
            // A sentinel actress for 'sentinel' tests.
            h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at, is_sentinel) VALUES (99, 'Various', 'S', '2024-01-01', 1)");
        });

        draftTitleRepo          = new DraftTitleRepository(jdbi);
        draftActressRepo        = new DraftActressRepository(jdbi);
        draftTitleActressesRepo = new DraftTitleActressesRepository(jdbi);
        service = new DraftPatchService(jdbi, draftTitleRepo, draftActressRepo, draftTitleActressesRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long insertDraft(long titleId) {
        DraftTitle dt = DraftTitle.builder()
                .titleId(titleId)
                .code("TST-1")
                .createdAt("2024-01-01T00:00:00Z")
                .updatedAt("2024-01-01T00:00:00Z")
                .build();
        return draftTitleRepo.insert(dt);
    }

    private void insertSlot(long draftId, String slug, String resolution) {
        draftTitleActressesRepo.replaceForDraft(draftId, List.of(
                new DraftTitleActress(draftId, slug, resolution)));
    }

    private void insertActress(String slug, String stageName) {
        DraftActress da = DraftActress.builder()
                .javdbSlug(slug)
                .stageName(stageName)
                .createdAt("2024-01-01T00:00:00Z")
                .updatedAt("2024-01-01T00:00:00Z")
                .build();
        draftActressRepo.upsertBySlug(da);
    }

    // ── Happy paths ────────────────────────────────────────────────────────────

    @Test
    void patch_pickResolution_setsLinkToExistingId() throws Exception {
        long draftId = insertDraft(1L);
        insertActress("abc1", "天海 麗");
        insertSlot(draftId, "abc1", "unresolved");

        String newToken = service.patch(1L, "2024-01-01T00:00:00Z",
                List.of(new DraftPatchService.CastResolutionEdit("abc1", "pick", 10L, null, null)),
                List.of());

        assertNotNull(newToken);

        // Slot resolution updated.
        var slots = draftTitleActressesRepo.findByDraftTitleId(draftId);
        assertEquals(1, slots.size());
        assertEquals("pick", slots.get(0).getResolution());

        // Actress link_to_existing_id set.
        var actress = draftActressRepo.findBySlug("abc1");
        assertTrue(actress.isPresent());
        assertEquals(10L, actress.get().getLinkToExistingId());

        // updated_at bumped.
        var updatedDraft = draftTitleRepo.findByTitleId(1L);
        assertTrue(updatedDraft.isPresent());
        assertEquals(newToken, updatedDraft.get().getUpdatedAt());
    }

    @Test
    void patch_createNewResolution_storesEnglishName() throws Exception {
        long draftId = insertDraft(1L);
        insertActress("xyz2", "田中 花");
        insertSlot(draftId, "xyz2", "unresolved");

        String newToken = service.patch(1L, "2024-01-01T00:00:00Z",
                List.of(new DraftPatchService.CastResolutionEdit("xyz2", "create_new", null, "Tanaka", "Hana")),
                List.of());

        assertNotNull(newToken);

        var slots = draftTitleActressesRepo.findByDraftTitleId(draftId);
        assertEquals("create_new", slots.get(0).getResolution());

        var actress = draftActressRepo.findBySlug("xyz2");
        assertTrue(actress.isPresent());
        assertEquals("Tanaka", actress.get().getEnglishLastName());
        assertEquals("Hana",   actress.get().getEnglishFirstName());
        assertNull(actress.get().getLinkToExistingId());
    }

    @Test
    void patch_skipResolution_accepted() throws Exception {
        long draftId = insertDraft(1L);
        insertActress("s1", "鈴木");
        insertSlot(draftId, "s1", "unresolved");

        service.patch(1L, "2024-01-01T00:00:00Z",
                List.of(new DraftPatchService.CastResolutionEdit("s1", "skip", null, null, null)),
                List.of());

        var slots = draftTitleActressesRepo.findByDraftTitleId(draftId);
        assertEquals("skip", slots.get(0).getResolution());
    }

    @Test
    void patch_sentinelResolution_validSentinel_accepted() throws Exception {
        long draftId = insertDraft(1L);
        insertActress("sen1", null);
        insertSlot(draftId, "sen1", "unresolved");

        service.patch(1L, "2024-01-01T00:00:00Z",
                List.of(new DraftPatchService.CastResolutionEdit("sen1", "sentinel:99", null, null, null)),
                List.of());

        var slots = draftTitleActressesRepo.findByDraftTitleId(draftId);
        assertEquals("sentinel:99", slots.get(0).getResolution());
    }

    @Test
    void patch_unresolvedResolution_accepted() throws Exception {
        long draftId = insertDraft(1L);
        insertActress("u1", "Unknown");
        insertSlot(draftId, "u1", "pick");

        // Reverting to unresolved is allowed.
        service.patch(1L, "2024-01-01T00:00:00Z",
                List.of(new DraftPatchService.CastResolutionEdit("u1", "unresolved", null, null, null)),
                List.of());

        var slots = draftTitleActressesRepo.findByDraftTitleId(draftId);
        assertEquals("unresolved", slots.get(0).getResolution());
    }

    @Test
    void patch_newActressNameEdit_updatesExistingSlug() throws Exception {
        long draftId = insertDraft(1L);
        insertActress("na1", "田中 花");
        insertSlot(draftId, "na1", "create_new");

        // Name edit without changing resolution.
        service.patch(1L, "2024-01-01T00:00:00Z",
                List.of(),
                List.of(new DraftPatchService.NewActressEdit("na1", "Tanaka", "Hana")));

        var actress = draftActressRepo.findBySlug("na1");
        assertTrue(actress.isPresent());
        assertEquals("Tanaka", actress.get().getEnglishLastName());
        assertEquals("田中 花", actress.get().getStageName()); // stage_name preserved
    }

    @Test
    void patch_multipleSlots_onlyTargetedSlotUpdated() throws Exception {
        long draftId = insertDraft(1L);
        insertActress("a1", "A");
        insertActress("a2", "B");
        // Insert both slots.
        draftTitleActressesRepo.replaceForDraft(draftId, List.of(
                new DraftTitleActress(draftId, "a1", "unresolved"),
                new DraftTitleActress(draftId, "a2", "unresolved")));

        service.patch(1L, "2024-01-01T00:00:00Z",
                List.of(new DraftPatchService.CastResolutionEdit("a1", "pick", 10L, null, null)),
                List.of());

        var slots = draftTitleActressesRepo.findByDraftTitleId(draftId);
        assertEquals(2, slots.size());
        var bySlug = new java.util.HashMap<String, String>();
        for (var s : slots) bySlug.put(s.getJavdbSlug(), s.getResolution());
        assertEquals("pick",       bySlug.get("a1"));
        assertEquals("unresolved", bySlug.get("a2"));
    }

    @Test
    void patch_nullExpectedUpdatedAt_skipsLockCheck() throws Exception {
        insertDraft(1L);
        // Null token → no lock check; should succeed unconditionally.
        assertDoesNotThrow(() ->
                service.patch(1L, null, List.of(), List.of()));
    }

    // ── DraftNotFoundException ─────────────────────────────────────────────────

    @Test
    void patch_noDraft_throwsDraftNotFoundException() {
        assertThrows(DraftNotFoundException.class, () ->
                service.patch(999L, null, List.of(), List.of()));
    }

    // ── Optimistic lock ────────────────────────────────────────────────────────

    @Test
    void patch_optimisticLockConflict_throwsOptimisticLockException() {
        insertDraft(1L);
        assertThrows(OptimisticLockException.class, () ->
                service.patch(1L, "stale-token", List.of(), List.of()));
    }

    // ── Validation error codes ─────────────────────────────────────────────────

    @Test
    void validate_pickMissingLinkId_returnsError() {
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", "pick", null, null, null)),
                List.of());
        assertTrue(errors.contains("PICK_MISSING_LINK_ID"));
    }

    @Test
    void validate_createNewMissingLastName_returnsError() {
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", "create_new", null, null, null)),
                List.of());
        assertTrue(errors.contains("CREATE_NEW_MISSING_LAST_NAME"));
    }

    @Test
    void validate_createNewBlankLastName_returnsError() {
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", "create_new", null, "  ", null)),
                List.of());
        assertTrue(errors.contains("CREATE_NEW_MISSING_LAST_NAME"));
    }

    @Test
    void validate_sentinelInvalidId_returnsError() {
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", "sentinel:not-a-number", null, null, null)),
                List.of());
        assertTrue(errors.contains("SENTINEL_INVALID_ID"));
    }

    @Test
    void validate_sentinelNotFlagged_returnsError() {
        // actress id=10 exists but is NOT a sentinel.
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", "sentinel:10", null, null, null)),
                List.of());
        assertTrue(errors.contains("SENTINEL_NOT_FLAGGED"));
    }

    @Test
    void validate_validSentinel_noError() {
        // actress id=99 is a sentinel.
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", "sentinel:99", null, null, null)),
                List.of());
        assertFalse(errors.contains("SENTINEL_NOT_FLAGGED"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_unknownResolutionKind_returnsError() {
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", "teleport", null, null, null)),
                List.of());
        assertTrue(errors.contains("UNKNOWN_RESOLUTION_KIND"));
    }

    @Test
    void validate_nullResolution_returnsError() {
        var errors = service.validate(
                List.of(new DraftPatchService.CastResolutionEdit("s", null, null, null, null)),
                List.of());
        assertTrue(errors.contains("RESOLUTION_NULL"));
    }

    @Test
    void validate_newActressMissingLastName_returnsError() {
        var errors = service.validate(
                List.of(),
                List.of(new DraftPatchService.NewActressEdit("s", null, "First")));
        assertTrue(errors.contains("CREATE_NEW_MISSING_LAST_NAME"));
    }

    @Test
    void validate_skipAndUnresolved_noErrors() {
        var errors = service.validate(
                List.of(
                        new DraftPatchService.CastResolutionEdit("s1", "skip",       null, null, null),
                        new DraftPatchService.CastResolutionEdit("s2", "unresolved", null, null, null)),
                List.of());
        assertTrue(errors.isEmpty());
    }

    @Test
    void patch_invalidResolutionShape_throwsPatchValidationException() {
        insertDraft(1L);
        assertThrows(DraftPatchService.PatchValidationException.class, () ->
                service.patch(1L, null,
                        List.of(new DraftPatchService.CastResolutionEdit("s", "pick", null, null, null)),
                        List.of()));
    }
}
