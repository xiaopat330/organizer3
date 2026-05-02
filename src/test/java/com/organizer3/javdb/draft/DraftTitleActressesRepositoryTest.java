package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DraftTitleActressesRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private DraftTitleActressesRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        repo = new DraftTitleActressesRepository(jdbi);

        // Seed a titles row, a draft_titles row, and two draft_actresses rows.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)");
            h.execute("INSERT INTO draft_titles(id, title_id, code, created_at, updated_at) " +
                      "VALUES (1, 1, 'TST-1', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_actresses(javdb_slug, stage_name, created_at, updated_at) " +
                      "VALUES ('slug_a', '女優A', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_actresses(javdb_slug, stage_name, created_at, updated_at) " +
                      "VALUES ('slug_b', '女優B', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
            h.execute("INSERT INTO draft_actresses(javdb_slug, stage_name, created_at, updated_at) " +
                      "VALUES ('slug_c', '女優C', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── replaceForDraft ────────────────────────────────────────────────────────

    @Test
    void replaceForDraft_insertsNewResolutions() {
        List<DraftTitleActress> resolutions = List.of(
                new DraftTitleActress(1L, "slug_a", "unresolved"),
                new DraftTitleActress(1L, "slug_b", "pick")
        );
        repo.replaceForDraft(1L, resolutions);

        List<DraftTitleActress> found = repo.findByDraftTitleId(1L);
        assertEquals(2, found.size());
    }

    @Test
    void replaceForDraft_replacesExistingResolutions() {
        repo.replaceForDraft(1L, List.of(
                new DraftTitleActress(1L, "slug_a", "unresolved"),
                new DraftTitleActress(1L, "slug_b", "unresolved")
        ));

        // Replace with a different set.
        repo.replaceForDraft(1L, List.of(
                new DraftTitleActress(1L, "slug_c", "pick")
        ));

        List<DraftTitleActress> found = repo.findByDraftTitleId(1L);
        assertEquals(1, found.size());
        assertEquals("slug_c", found.get(0).getJavdbSlug());
        assertEquals("pick", found.get(0).getResolution());
    }

    @Test
    void replaceForDraft_withEmptyListClearsAllRows() {
        repo.replaceForDraft(1L, List.of(
                new DraftTitleActress(1L, "slug_a", "pick")
        ));

        repo.replaceForDraft(1L, List.of());

        List<DraftTitleActress> found = repo.findByDraftTitleId(1L);
        assertTrue(found.isEmpty(), "replacing with empty list must clear all rows");
    }

    // ── findByDraftTitleId ─────────────────────────────────────────────────────

    @Test
    void findByDraftTitleId_returnsEmptyWhenNoneExist() {
        List<DraftTitleActress> found = repo.findByDraftTitleId(1L);
        assertTrue(found.isEmpty());
    }

    @Test
    void findByDraftTitleId_mapsResolutionCorrectly() {
        repo.replaceForDraft(1L, List.of(
                new DraftTitleActress(1L, "slug_a", "sentinel:42")
        ));

        DraftTitleActress row = repo.findByDraftTitleId(1L).get(0);
        assertEquals(1L, row.getDraftTitleId());
        assertEquals("slug_a", row.getJavdbSlug());
        assertEquals("sentinel:42", row.getResolution());
    }

    // ── ON DELETE CASCADE: deleting draft_titles cascades to rows here ─────────

    @Test
    void deletingDraftTitleCascadesToActressRows() {
        repo.replaceForDraft(1L, List.of(
                new DraftTitleActress(1L, "slug_a", "unresolved"),
                new DraftTitleActress(1L, "slug_b", "unresolved")
        ));

        jdbi.useHandle(h -> h.execute("DELETE FROM draft_titles WHERE id = 1"));

        List<DraftTitleActress> remaining = repo.findByDraftTitleId(1L);
        assertTrue(remaining.isEmpty(),
                "draft_title_actresses rows must be cascade-deleted with their parent draft_titles row");
    }
}
