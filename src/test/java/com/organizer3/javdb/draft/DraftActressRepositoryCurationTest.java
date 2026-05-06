package com.organizer3.javdb.draft;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftActressRepository.PendingKanjiRow;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Near-Miss curation repo methods added in slice F.
 */
class DraftActressRepositoryCurationTest {

    private Connection connection;
    private Jdbi jdbi;
    private DraftActressRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute("PRAGMA foreign_keys = ON"));
        new SchemaInitializer(jdbi).initialize();
        repo = new DraftActressRepository(jdbi);

        jdbi.useHandle(h ->
                h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) " +
                          "VALUES (42, 'Real Actress', 'LIBRARY', '2024-01-01')"));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private void insert(String slug, String kanji, Long xid, String linkDraft, String last) {
        insert(slug, kanji, xid, linkDraft, last, "2024-01-01T00:00:00Z");
    }

    private void insert(String slug, String kanji, Long xid, String linkDraft, String last,
                        String createdAt) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO draft_actresses
                    (javdb_slug, stage_name, english_first_name, english_last_name,
                     link_to_existing_id, link_to_draft_slug, created_at, updated_at)
                VALUES (:slug, :kanji, NULL, :last, :xid, :linkDraft, :createdAt, :createdAt)
                """)
                .bind("slug",      slug)
                .bind("kanji",     kanji)
                .bind("last",      last)
                .bind("xid",       xid)
                .bind("linkDraft", linkDraft)
                .bind("createdAt", createdAt)
                .execute());
    }

    // ── cascadeAliasResolution ───────────────────────────────────────────────

    @Test
    void cascadeAliasResolution_updatesUnresolvedRows() {
        insert("da1", "テスト", null, null, null);
        insert("da2", "テスト", null, null, null);

        int updated = repo.cascadeAliasResolution("テスト", 42L, "First", "Last");

        assertEquals(2, updated);
        assertEquals(42L, repo.findBySlug("da1").orElseThrow().getLinkToExistingId());
        assertEquals(42L, repo.findBySlug("da2").orElseThrow().getLinkToExistingId());
    }

    @Test
    void cascadeAliasResolution_skipsAlreadyLinkedRows() {
        insert("da1", "テスト", null, null, null);
        insert("da_linked", "テスト", 42L, null, "Linked");  // already linked

        int updated = repo.cascadeAliasResolution("テスト", 42L, "First", "Last");

        assertEquals(1, updated);
        // da_linked remains as-is
        assertEquals("Linked", repo.findBySlug("da_linked").orElseThrow().getEnglishLastName());
    }

    @Test
    void cascadeAliasResolution_skipsRowsWithLinkToDraftSlug() {
        insert("da1", "テスト", null, null, null);
        insert("primary", "テスト", null, null, null);
        insert("sibling", "テスト", null, "primary", null);  // has link_to_draft_slug

        int updated = repo.cascadeAliasResolution("テスト", 42L, "First", "Last");

        assertEquals(2, updated);  // da1 + primary; sibling skipped
        assertNull(repo.findBySlug("sibling").orElseThrow().getLinkToExistingId());
    }

    // ── cascadeCanonicalResolution ───────────────────────────────────────────

    @Test
    void cascadeCanonicalResolution_setsSiblingLinkToDraftSlug() {
        insert("prim", "テスト", null, null, null);
        insert("sib1", "テスト", null, null, null);
        insert("sib2", "テスト", null, null, null);

        int updated = repo.cascadeCanonicalResolution("テスト", "prim", "F", "L");

        assertEquals(3, updated);
        assertNull(repo.findBySlug("prim").orElseThrow().getLinkToDraftSlug());
        assertEquals("prim", repo.findBySlug("sib1").orElseThrow().getLinkToDraftSlug());
        assertEquals("prim", repo.findBySlug("sib2").orElseThrow().getLinkToDraftSlug());
    }

    @Test
    void cascadeCanonicalResolution_doesNotTouchPreResolvedSiblings() {
        insert("prim", "テスト", null, null, null);
        insert("sib_resolved", "テスト", 42L, null, "Old");  // already linked to existing

        int updated = repo.cascadeCanonicalResolution("テスト", "prim", "F", "L");

        assertEquals(1, updated);  // only primary
        assertEquals("Old", repo.findBySlug("sib_resolved").orElseThrow().getEnglishLastName());
    }

    // ── countUnresolvedByKanji ───────────────────────────────────────────────

    @Test
    void countUnresolvedByKanji_countsOnlyMatchingUnresolved() {
        insert("da1", "テスト", null, null, null);
        insert("da2", "テスト", null, null, null);
        insert("da_last", "テスト", null, null, "HasLast");  // has last name → excluded
        insert("da_linked", "テスト", 42L, null, null);     // linked → excluded
        insert("other", "他の", null, null, null);           // different kanji

        assertEquals(2, repo.countUnresolvedByKanji("テスト"));
    }

    @Test
    void countUnresolvedByKanji_returnsZeroWhenNone() {
        assertEquals(0, repo.countUnresolvedByKanji("存在しない"));
    }

    // ── findOldestUnresolvedSlugByKanji ──────────────────────────────────────

    @Test
    void findOldestUnresolvedSlug_picksLowestCreatedAt() {
        insert("middle", "テスト", null, null, null, "2026-02-01T00:00:00Z");
        insert("oldest", "テスト", null, null, null, "2026-01-01T00:00:00Z");
        insert("newest", "テスト", null, null, null, "2026-03-01T00:00:00Z");

        assertEquals("oldest", repo.findOldestUnresolvedSlugByKanji("テスト").orElseThrow());
    }

    @Test
    void findOldestUnresolvedSlug_skipsResolvedRows() {
        insert("primaryRef", "他の",  null, null, null, "2026-01-01T00:00:00Z");  // FK target
        insert("linked",     "テスト", 42L,  null, null,            "2026-01-01T00:00:00Z");
        insert("hasLast",    "テスト", null, null, "Last",          "2026-01-02T00:00:00Z");
        insert("hasDraftFK", "テスト", null, "primaryRef", null,    "2026-01-03T00:00:00Z");
        insert("real",       "テスト", null, null, null,            "2026-02-01T00:00:00Z");

        assertEquals("real", repo.findOldestUnresolvedSlugByKanji("テスト").orElseThrow());
    }

    @Test
    void findOldestUnresolvedSlug_emptyWhenNoneMatch() {
        assertTrue(repo.findOldestUnresolvedSlugByKanji("存在しない").isEmpty());
    }

    // ── findPendingKanjiGroups ───────────────────────────────────────────────

    @Test
    void findPendingKanjiGroups_returnsGroupsSortedByCountDescOldestAsc() {
        insert("a1", "テスト", null, null, null);
        insert("a2", "テスト", null, null, null);
        insert("b1", "他の", null, null, null);
        insert("resolved", "テスト", 42L, null, null);  // excluded

        List<PendingKanjiRow> groups = repo.findPendingKanjiGroups();

        assertEquals(2, groups.size());
        assertEquals("テスト", groups.get(0).kanji());
        assertEquals(2, groups.get(0).count());
        assertEquals("他の", groups.get(1).kanji());
        assertEquals(1, groups.get(1).count());
    }

    @Test
    void findPendingKanjiGroups_excludesRowsWithLastName() {
        insert("da1", "テスト", null, null, "HasLast");

        List<PendingKanjiRow> groups = repo.findPendingKanjiGroups();
        assertTrue(groups.isEmpty());
    }
}
