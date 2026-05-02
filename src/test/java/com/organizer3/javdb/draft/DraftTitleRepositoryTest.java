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

class DraftTitleRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private DraftTitleRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        repo = new DraftTitleRepository(jdbi);

        // Seed a canonical titles row (required FK for draft_titles.title_id).
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)");
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (2, 'TST-2', 'TST', 'TST', 2)");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private static DraftTitle sample(long titleId) {
        return DraftTitle.builder()
                .titleId(titleId)
                .code("TST-" + titleId)
                .titleOriginal("Original Title")
                .titleEnglish("English Title")
                .releaseDate("2024-06-01")
                .notes("test note")
                .grade("A")
                .gradeSource("enrichment")
                .upstreamChanged(false)
                .lastValidationError(null)
                .createdAt("2024-06-01T00:00:00Z")
                .updatedAt("2024-06-01T00:00:00Z")
                .build();
    }

    // ── insert + findById ──────────────────────────────────────────────────────

    @Test
    void insertAssignsId() {
        long id = repo.insert(sample(1));
        assertTrue(id > 0, "generated id must be positive");
    }

    @Test
    void findById_returnsInsertedDraft() {
        long id = repo.insert(sample(1));
        Optional<DraftTitle> found = repo.findById(id);
        assertTrue(found.isPresent(), "should find the inserted draft");
        assertEquals("TST-1", found.get().getCode());
        assertEquals("Original Title", found.get().getTitleOriginal());
    }

    @Test
    void findById_emptyWhenNotFound() {
        Optional<DraftTitle> found = repo.findById(9999L);
        assertTrue(found.isEmpty());
    }

    // ── findByTitleId ──────────────────────────────────────────────────────────

    @Test
    void findByTitleId_returnsActiveDraft() {
        repo.insert(sample(1));
        Optional<DraftTitle> found = repo.findByTitleId(1L);
        assertTrue(found.isPresent());
        assertEquals(1L, found.get().getTitleId());
    }

    @Test
    void findByTitleId_emptyWhenNoDraft() {
        Optional<DraftTitle> found = repo.findByTitleId(99L);
        assertTrue(found.isEmpty());
    }

    // ── unique-index enforcement ───────────────────────────────────────────────

    @Test
    void insertSecondDraftForSameTitleThrows() {
        repo.insert(sample(1));
        assertThrows(Exception.class, () -> repo.insert(sample(1)),
                "second draft for the same title_id should violate unique index");
    }

    // ── update with optimistic lock ────────────────────────────────────────────

    @Test
    void update_succeedsWhenTokenMatches() {
        long id = repo.insert(sample(1));
        DraftTitle original = repo.findById(id).orElseThrow();

        DraftTitle updated = original.toBuilder()
                .notes("updated note")
                .updatedAt("2024-07-01T00:00:00Z")
                .build();
        assertDoesNotThrow(() -> repo.update(updated, original.getUpdatedAt()));

        DraftTitle reloaded = repo.findById(id).orElseThrow();
        assertEquals("updated note", reloaded.getNotes());
        assertEquals("2024-07-01T00:00:00Z", reloaded.getUpdatedAt());
    }

    @Test
    void update_throwsOptimisticLockExceptionOnTokenMismatch() {
        long id = repo.insert(sample(1));
        DraftTitle original = repo.findById(id).orElseThrow();

        DraftTitle updated = original.toBuilder().notes("oops").build();
        assertThrows(OptimisticLockException.class,
                () -> repo.update(updated, "1970-01-01T00:00:00Z"),
                "stale token must throw OptimisticLockException");
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    void delete_removesRow() {
        long id = repo.insert(sample(1));
        repo.delete(id);
        assertTrue(repo.findById(id).isEmpty(), "row must be gone after delete");
    }

    @Test
    void delete_isNoOpWhenNotFound() {
        assertDoesNotThrow(() -> repo.delete(9999L));
    }

    // ── listAll ────────────────────────────────────────────────────────────────

    @Test
    void listAll_returnsPagedResults() {
        repo.insert(sample(1));
        repo.insert(sample(2));

        List<DraftTitle> page1 = repo.listAll(0, 1);
        assertEquals(1, page1.size());

        List<DraftTitle> all = repo.listAll(0, 10);
        assertEquals(2, all.size());
    }

    @Test
    void listAll_emptyWhenNoRows() {
        List<DraftTitle> result = repo.listAll(0, 10);
        assertTrue(result.isEmpty());
    }

    // ── setUpstreamChanged ─────────────────────────────────────────────────────

    @Test
    void setUpstreamChanged_stampsFlag() {
        long id = repo.insert(sample(1));
        repo.setUpstreamChanged(1L, "2024-08-01T00:00:00Z");

        DraftTitle updated = repo.findById(id).orElseThrow();
        assertTrue(updated.isUpstreamChanged());
        assertEquals("2024-08-01T00:00:00Z", updated.getUpdatedAt());
    }

    @Test
    void setUpstreamChanged_isNoOpWhenNoDraft() {
        // Should not throw even if no draft exists for the title.
        assertDoesNotThrow(() -> repo.setUpstreamChanged(9999L, "2024-08-01T00:00:00Z"));
    }

    // ── reapStale ──────────────────────────────────────────────────────────────

    @Test
    void reapStale_deletesOldRowsOnly() {
        // Insert a row with a created_at far in the past.
        DraftTitle old = sample(1).toBuilder()
                .createdAt("2020-01-01T00:00:00Z")
                .updatedAt("2020-01-01T00:00:00Z")
                .build();
        long oldId = repo.insert(old);

        // Insert a recent row.
        DraftTitle recent = sample(2).toBuilder()
                .createdAt("2099-12-31T00:00:00Z")
                .updatedAt("2099-12-31T00:00:00Z")
                .build();
        long recentId = repo.insert(recent);

        int reaped = repo.reapStale(30);
        assertEquals(1, reaped, "only the old row should be reaped");
        assertTrue(repo.findById(oldId).isEmpty(), "old row must be gone");
        assertTrue(repo.findById(recentId).isPresent(), "recent row must survive");
    }

    @Test
    void reapStale_filtersOnUpdatedAtNotCreatedAt() {
        // Regression: spec §9.2 requires filtering on updated_at so a draft
        // created long ago but actively edited survives the sweep.
        DraftTitle activelyEdited = sample(1).toBuilder()
                .createdAt("2020-01-01T00:00:00Z")           // ancient
                .updatedAt("2099-12-31T00:00:00Z")           // recent
                .build();
        long id = repo.insert(activelyEdited);

        assertEquals(0, repo.reapStale(30),
                "draft with recent updated_at must survive even if created_at is old");
        assertTrue(repo.findById(id).isPresent());
    }

    @Test
    void reapStale_returnsZeroWhenNothingStale() {
        DraftTitle recent = sample(1).toBuilder()
                .createdAt("2099-12-31T00:00:00Z")
                .updatedAt("2099-12-31T00:00:00Z")
                .build();
        repo.insert(recent);
        assertEquals(0, repo.reapStale(30));
    }

    // ── ON DELETE CASCADE: deleting titles row removes draft ──────────────────

    @Test
    void deletingCanonicalTitleCascadesToDraft() {
        long draftId = repo.insert(sample(1));
        jdbi.useHandle(h -> h.execute("DELETE FROM titles WHERE id = 1"));
        assertTrue(repo.findById(draftId).isEmpty(),
                "draft_titles row must be cascade-deleted when canonical title is deleted");
    }
}
