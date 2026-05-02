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

class DraftActressRepositoryTest {

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

        // Seed an actress row for tests that need a valid FK link_to_existing_id.
        jdbi.useHandle(h ->
                h.execute("INSERT INTO actresses(id, canonical_name, tier, first_seen_at) " +
                          "VALUES (42, 'Real Actress', 'LIBRARY', '2024-01-01')"));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private static DraftActress sample(String slug) {
        return DraftActress.builder()
                .javdbSlug(slug)
                .stageName("テスト女優")
                .englishFirstName("Akari")
                .englishLastName("Sato")
                .linkToExistingId(null)
                .createdAt("2024-06-01T00:00:00Z")
                .updatedAt("2024-06-01T00:00:00Z")
                .lastValidationError(null)
                .build();
    }

    // ── upsertBySlug + findBySlug ──────────────────────────────────────────────

    @Test
    void upsertBySlug_insertsNewRow() {
        repo.upsertBySlug(sample("abc12"));
        Optional<DraftActress> found = repo.findBySlug("abc12");
        assertTrue(found.isPresent());
        assertEquals("abc12", found.get().getJavdbSlug());
        assertEquals("テスト女優", found.get().getStageName());
        assertEquals("Akari", found.get().getEnglishFirstName());
        assertEquals("Sato", found.get().getEnglishLastName());
    }

    @Test
    void findBySlug_emptyWhenNotFound() {
        Optional<DraftActress> found = repo.findBySlug("nosuchslug");
        assertTrue(found.isEmpty());
    }

    @Test
    void upsertBySlug_updatesEditableColumnsOnConflict() {
        repo.upsertBySlug(sample("abc12"));

        // Second upsert: change editable fields but keep slug + stage_name.
        DraftActress updated = sample("abc12").toBuilder()
                .englishLastName("Yamamoto")
                .linkToExistingId(42L)
                .updatedAt("2024-07-01T00:00:00Z")
                .build();
        repo.upsertBySlug(updated);

        DraftActress reloaded = repo.findBySlug("abc12").orElseThrow();
        assertEquals("Yamamoto", reloaded.getEnglishLastName());
        assertEquals(42L, reloaded.getLinkToExistingId());
        assertEquals("2024-07-01T00:00:00Z", reloaded.getUpdatedAt());
        // stage_name preserved from first insert (not overwritten by upsert).
        assertEquals("テスト女優", reloaded.getStageName());
    }

    @Test
    void upsertBySlug_handlesNullLinkToExistingId() {
        repo.upsertBySlug(sample("abc12"));
        DraftActress found = repo.findBySlug("abc12").orElseThrow();
        assertNull(found.getLinkToExistingId());
    }

    // ── reapOrphans ────────────────────────────────────────────────────────────

    @Test
    void reapOrphans_deletesActressWithNoReferences() {
        repo.upsertBySlug(sample("orphan1"));
        repo.upsertBySlug(sample("orphan2"));

        int reaped = repo.reapOrphans();
        assertEquals(2, reaped, "both unreferenced actresses should be reaped");
        assertTrue(repo.findBySlug("orphan1").isEmpty());
        assertTrue(repo.findBySlug("orphan2").isEmpty());
    }

    @Test
    void reapOrphans_preservesActressWithReference() {
        // Seed canonical title + draft title + draft_title_actresses row.
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO titles(id, code, base_code, label, seq_num) VALUES (1, 'TST-1', 'TST', 'TST', 1)");
            h.execute("INSERT INTO draft_titles(id, title_id, code, created_at, updated_at) VALUES (1, 1, 'TST-1', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z')");
        });
        repo.upsertBySlug(sample("referenced"));
        jdbi.useHandle(h ->
                h.execute("INSERT INTO draft_title_actresses(draft_title_id, javdb_slug, resolution) VALUES (1, 'referenced', 'unresolved')"));

        // Also insert an orphan that has no reference.
        repo.upsertBySlug(sample("orphan"));

        int reaped = repo.reapOrphans();
        assertEquals(1, reaped, "only the orphan should be reaped");
        assertTrue(repo.findBySlug("referenced").isPresent(), "referenced actress must survive");
        assertTrue(repo.findBySlug("orphan").isEmpty(), "orphan must be deleted");
    }

    @Test
    void reapOrphans_returnsZeroWhenTableEmpty() {
        assertEquals(0, repo.reapOrphans());
    }

    // ── actress with lastValidationError ──────────────────────────────────────

    @Test
    void upsertBySlug_persistsLastValidationError() {
        DraftActress withError = sample("abc12").toBuilder()
                .lastValidationError("english_last_name_required")
                .build();
        repo.upsertBySlug(withError);

        DraftActress found = repo.findBySlug("abc12").orElseThrow();
        assertEquals("english_last_name_required", found.getLastValidationError());
    }

    @Test
    void upsertBySlug_clearsLastValidationErrorOnUpdate() {
        DraftActress withError = sample("abc12").toBuilder()
                .lastValidationError("english_last_name_required")
                .build();
        repo.upsertBySlug(withError);

        DraftActress cleared = sample("abc12").toBuilder()
                .lastValidationError(null)
                .updatedAt("2024-07-01T00:00:00Z")
                .build();
        repo.upsertBySlug(cleared);

        DraftActress found = repo.findBySlug("abc12").orElseThrow();
        assertNull(found.getLastValidationError());
    }
}
