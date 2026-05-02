package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DraftGcService}.
 *
 * <p>Uses real in-memory SQLite (same pattern as other draft repo tests)
 * with a {@code @TempDir} scratch directory for cover files.
 */
class DraftGcServiceTest {

    @TempDir
    Path dataDir;

    private Connection connection;
    private Jdbi jdbi;
    private DraftTitleRepository draftTitleRepo;
    private DraftActressRepository draftActressRepo;
    private DraftTitleActressesRepository draftCastRepo;
    private DraftCoverScratchStore scratchStore;
    private DraftGcService gcService;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();

        draftTitleRepo  = new DraftTitleRepository(jdbi);
        draftActressRepo = new DraftActressRepository(jdbi);
        draftCastRepo   = new DraftTitleActressesRepository(jdbi);
        scratchStore    = new DraftCoverScratchStore(dataDir);
        gcService       = new DraftGcService(draftTitleRepo, draftActressRepo, scratchStore, 30);

        // Seed canonical titles rows required by draft_titles FK.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'T-1', 'T', 'T', 1)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'T-2', 'T', 'T', 2)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (3, 'T-3', 'T', 'T', 3)");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helper factories ────────────────────────────────────────────────────────

    private DraftTitle draftWithAge(long titleId, String createdAt) {
        return DraftTitle.builder()
                .titleId(titleId)
                .code("T-" + titleId)
                .upstreamChanged(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private DraftActress actress(String slug) {
        return DraftActress.builder()
                .javdbSlug(slug)
                .stageName("Test " + slug)
                .createdAt("2025-01-01T00:00:00Z")
                .updatedAt("2025-01-01T00:00:00Z")
                .build();
    }

    // ── stale draft reaping ────────────────────────────────────────────────────

    @Test
    void sweep_reapsStaleOldDraft() {
        long oldId = draftTitleRepo.insert(draftWithAge(1, "2020-01-01T00:00:00Z"));

        int total = gcService.sweep();

        assertTrue(total >= 1, "at least one item should be reaped");
        assertTrue(draftTitleRepo.findById(oldId).isEmpty(), "stale draft must be gone");
    }

    @Test
    void sweep_preservesRecentDraft() {
        long recentId = draftTitleRepo.insert(draftWithAge(1, "2099-12-31T00:00:00Z"));

        gcService.sweep();

        assertTrue(draftTitleRepo.findById(recentId).isPresent(), "recent draft must survive");
    }

    @Test
    void sweep_reaesStaleButNotRecent() {
        long oldId    = draftTitleRepo.insert(draftWithAge(1, "2020-01-01T00:00:00Z"));
        long recentId = draftTitleRepo.insert(draftWithAge(2, "2099-12-31T00:00:00Z"));

        gcService.sweep();

        assertTrue(draftTitleRepo.findById(oldId).isEmpty(),    "old draft must be reaped");
        assertTrue(draftTitleRepo.findById(recentId).isPresent(), "recent draft must survive");
    }

    // ── orphan actress reaping ─────────────────────────────────────────────────

    @Test
    void sweep_reapsOrphanActress() {
        // Actress with no draft_title_actresses reference — orphan.
        draftActressRepo.upsertBySlug(actress("orphan-slug"));

        int total = gcService.sweep();

        assertTrue(total >= 1);
        assertTrue(draftActressRepo.findBySlug("orphan-slug").isEmpty(),
                "orphan actress must be reaped");
    }

    @Test
    void sweep_preservesReferencedActress() {
        long draftId = draftTitleRepo.insert(draftWithAge(1, "2099-12-31T00:00:00Z"));
        draftActressRepo.upsertBySlug(actress("referenced-slug"));
        draftCastRepo.replaceForDraft(draftId,
                java.util.List.of(new DraftTitleActress(draftId, "referenced-slug", "unresolved")));

        gcService.sweep();

        assertTrue(draftActressRepo.findBySlug("referenced-slug").isPresent(),
                "referenced actress must not be reaped");
    }

    // ── orphan cover reaping ───────────────────────────────────────────────────

    @Test
    void sweep_reapsOrphanCoverFile() throws Exception {
        // Write a cover file for a draft id that does not exist in draft_titles.
        long nonExistentDraftId = 9999L;
        scratchStore.write(nonExistentDraftId, new byte[]{1, 2, 3});
        assertTrue(scratchStore.exists(nonExistentDraftId));

        gcService.sweep();

        assertFalse(scratchStore.exists(nonExistentDraftId),
                "cover for non-existent draft must be reaped");
    }

    @Test
    void sweep_preservesLiveCoverFile() throws Exception {
        long draftId = draftTitleRepo.insert(draftWithAge(1, "2099-12-31T00:00:00Z"));
        scratchStore.write(draftId, new byte[]{5, 6, 7});

        gcService.sweep();

        assertTrue(scratchStore.exists(draftId),
                "cover for live draft must not be reaped");
    }

    @Test
    void sweep_onlyReapsOrphanCover() throws Exception {
        long liveDraftId = draftTitleRepo.insert(draftWithAge(1, "2099-12-31T00:00:00Z"));
        long orphanDraftId = 8888L; // not in draft_titles

        scratchStore.write(liveDraftId,   new byte[]{1});
        scratchStore.write(orphanDraftId, new byte[]{2});

        gcService.sweep();

        assertTrue(scratchStore.exists(liveDraftId),    "live cover must survive");
        assertFalse(scratchStore.exists(orphanDraftId), "orphan cover must be reaped");
    }

    // ── end-to-end cascade test ────────────────────────────────────────────────

    /**
     * When a stale draft is reaped (step 1), the CASCADE removes its
     * draft_title_actresses row. On the same sweep call (step 2) the now-unreferenced
     * actress becomes an orphan and is also reaped.
     */
    @Test
    void sweep_staleDraftCascadeThenOrphanActressBothReapedInOneSweep() {
        // Insert stale draft with an associated actress.
        long draftId = draftTitleRepo.insert(draftWithAge(1, "2020-01-01T00:00:00Z"));
        draftActressRepo.upsertBySlug(actress("cascade-slug"));
        draftCastRepo.replaceForDraft(draftId,
                java.util.List.of(new DraftTitleActress(draftId, "cascade-slug", "unresolved")));

        // Confirm setup.
        assertTrue(draftTitleRepo.findById(draftId).isPresent());
        assertTrue(draftActressRepo.findBySlug("cascade-slug").isPresent());

        int total = gcService.sweep();

        // stale_drafts=1 + orphan_actresses=1 = at least 2.
        assertTrue(total >= 2, "expected at least 2 items reaped, got " + total);
        assertTrue(draftTitleRepo.findById(draftId).isEmpty(),      "stale draft must be gone");
        assertTrue(draftActressRepo.findBySlug("cascade-slug").isEmpty(),
                "cascade-orphaned actress must be gone after same sweep");
    }

    // ── return value and count ────────────────────────────────────────────────

    @Test
    void sweep_returnsZeroWhenNothingToReap() {
        // No drafts, no actresses, no covers.
        int total = gcService.sweep();
        assertEquals(0, total, "nothing to reap → total must be 0");
    }

    @Test
    void sweep_returnsSumOfAllCategories() throws Exception {
        // 1 stale draft (gets reaped) + 1 orphan actress + 1 orphan cover.
        draftTitleRepo.insert(draftWithAge(1, "2020-01-01T00:00:00Z")); // stale
        draftActressRepo.upsertBySlug(actress("unref-slug"));           // orphan actress
        scratchStore.write(7777L, new byte[]{1});                       // orphan cover

        int total = gcService.sweep();

        assertEquals(3, total, "1 stale draft + 1 orphan actress + 1 orphan cover = 3");
    }

    // ── scratch directory missing ─────────────────────────────────────────────

    @Test
    void sweep_handlesAbsentScratchDirGracefully() {
        // No files ever written — scratch dir doesn't exist yet.
        Path noDir = dataDir.resolve("new-data-dir");
        DraftCoverScratchStore emptyStore = new DraftCoverScratchStore(noDir);
        DraftGcService svc = new DraftGcService(draftTitleRepo, draftActressRepo, emptyStore, 30);

        assertDoesNotThrow(svc::sweep, "absent scratch dir must not cause an exception");
    }
}
