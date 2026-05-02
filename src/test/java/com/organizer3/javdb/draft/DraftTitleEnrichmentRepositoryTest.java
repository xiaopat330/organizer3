package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DraftTitleEnrichmentRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private DraftTitleEnrichmentRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        repo = new DraftTitleEnrichmentRepository(jdbi);

        // Seed required FK rows.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)");
            h.execute("INSERT INTO draft_titles(id, title_id, code, created_at, updated_at) " +
                      "VALUES (1, 1, 'TST-1', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private static DraftEnrichment sample(long draftTitleId) {
        return DraftEnrichment.builder()
                .draftTitleId(draftTitleId)
                .javdbSlug("test-slug")
                .castJson("[{\"name\":\"テスト\"}]")
                .maker("S1 NO.1 STYLE")
                .series("Test Series")
                .coverUrl("https://example.com/cover.jpg")
                .tagsJson("[\"Big Tits\",\"Solowork\"]")
                .ratingAvg(4.5)
                .ratingCount(120)
                .resolverSource("auto_enriched")
                .updatedAt("2024-06-01T00:00:00Z")
                .build();
    }

    // ── upsert + findByDraftId ─────────────────────────────────────────────────

    @Test
    void upsert_insertsAndFindByDraftIdReturnsIt() {
        repo.upsert(1L, sample(1L));

        Optional<DraftEnrichment> found = repo.findByDraftId(1L);
        assertTrue(found.isPresent());

        DraftEnrichment e = found.get();
        assertEquals(1L, e.getDraftTitleId());
        assertEquals("test-slug", e.getJavdbSlug());
        assertEquals("S1 NO.1 STYLE", e.getMaker());
        assertEquals("Test Series", e.getSeries());
        assertEquals("https://example.com/cover.jpg", e.getCoverUrl());
        assertEquals("[\"Big Tits\",\"Solowork\"]", e.getTagsJson());
        assertEquals(4.5, e.getRatingAvg());
        assertEquals(120, e.getRatingCount());
        assertEquals("auto_enriched", e.getResolverSource());
    }

    @Test
    void findByDraftId_emptyWhenNotFound() {
        Optional<DraftEnrichment> found = repo.findByDraftId(9999L);
        assertTrue(found.isEmpty());
    }

    @Test
    void upsert_replacesExistingRow() {
        repo.upsert(1L, sample(1L));

        DraftEnrichment updated = sample(1L).toBuilder()
                .maker("Prestige")
                .ratingAvg(3.8)
                .updatedAt("2024-07-01T00:00:00Z")
                .build();
        repo.upsert(1L, updated);

        DraftEnrichment reloaded = repo.findByDraftId(1L).orElseThrow();
        assertEquals("Prestige", reloaded.getMaker());
        assertEquals(3.8, reloaded.getRatingAvg());
        assertEquals("2024-07-01T00:00:00Z", reloaded.getUpdatedAt());
    }

    // ── null-tolerant fields ───────────────────────────────────────────────────

    @Test
    void upsert_handlesNullRatingAvg() {
        DraftEnrichment noRating = sample(1L).toBuilder()
                .ratingAvg(null)
                .ratingCount(null)
                .build();
        repo.upsert(1L, noRating);

        DraftEnrichment found = repo.findByDraftId(1L).orElseThrow();
        assertNull(found.getRatingAvg());
        assertNull(found.getRatingCount());
    }

    @Test
    void upsert_handlesNullOptionalFields() {
        DraftEnrichment minimal = DraftEnrichment.builder()
                .draftTitleId(1L)
                .updatedAt("2024-06-01T00:00:00Z")
                .build();
        repo.upsert(1L, minimal);

        DraftEnrichment found = repo.findByDraftId(1L).orElseThrow();
        assertNull(found.getJavdbSlug());
        assertNull(found.getCastJson());
        assertNull(found.getMaker());
        assertNull(found.getSeries());
        assertNull(found.getCoverUrl());
        assertNull(found.getTagsJson());
    }

    // ── ON DELETE CASCADE: deleting draft_titles cascades to enrichment ────────

    @Test
    void deletingDraftTitleCascadesToEnrichment() {
        repo.upsert(1L, sample(1L));
        assertTrue(repo.findByDraftId(1L).isPresent(), "enrichment must exist before cascade");

        jdbi.useHandle(h -> h.execute("DELETE FROM draft_titles WHERE id = 1"));

        assertTrue(repo.findByDraftId(1L).isEmpty(),
                "enrichment row must be cascade-deleted with its parent draft_titles row");
    }
}
