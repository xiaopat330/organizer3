package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.notes.EntityType;
import com.organizer3.notes.Note;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbiNoteRepositoryTest {

    private JdbiNoteRepository repo;
    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiNoteRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── find ──────────────────────────────────────────────────────────────────

    @Test
    void find_returnsEmptyWhenNoNote() {
        Optional<Note> result = repo.find(EntityType.ACTRESS, "999");
        assertTrue(result.isEmpty());
    }

    @Test
    void find_returnsNoteAfterUpsert() {
        repo.upsert(EntityType.ACTRESS, "42", "check her resume");

        Optional<Note> result = repo.find(EntityType.ACTRESS, "42");

        assertTrue(result.isPresent());
        assertEquals("check her resume", result.get().body());
        assertEquals(EntityType.ACTRESS, result.get().entityType());
        assertEquals("42", result.get().entityId());
    }

    @Test
    void find_doesNotCrossEntityTypes() {
        repo.upsert(EntityType.ACTRESS, "42", "actress note");

        assertTrue(repo.find(EntityType.TITLE, "42").isEmpty(),
                "Same id on a different type should not match");
    }

    // ── upsert (insert + update) ──────────────────────────────────────────────

    @Test
    void upsert_insertsNewRow() {
        repo.upsert(EntityType.TITLE, "ABP-123", "favorite scene at 42:18");

        Optional<Note> note = repo.find(EntityType.TITLE, "ABP-123");
        assertTrue(note.isPresent());
        assertEquals("favorite scene at 42:18", note.get().body());
    }

    @Test
    void upsert_updatesExistingBodyPreservesCreatedAt() throws InterruptedException {
        repo.upsert(EntityType.TITLE, "ABP-123", "original body");
        long createdAt = repo.find(EntityType.TITLE, "ABP-123").get().createdAt();

        Thread.sleep(2); // ensure updated_at advances
        repo.upsert(EntityType.TITLE, "ABP-123", "updated body");

        Note note = repo.find(EntityType.TITLE, "ABP-123").get();
        assertEquals("updated body", note.body());
        assertEquals(createdAt, note.createdAt(), "created_at must not change on update");
        assertTrue(note.updatedAt() >= createdAt, "updated_at must be >= created_at");
    }

    @Test
    void upsert_updatedAtAdvancesOnUpdate() throws InterruptedException {
        repo.upsert(EntityType.ACTRESS, "7", "first");
        long firstUpdated = repo.find(EntityType.ACTRESS, "7").get().updatedAt();

        Thread.sleep(2);
        repo.upsert(EntityType.ACTRESS, "7", "second");
        long secondUpdated = repo.find(EntityType.ACTRESS, "7").get().updatedAt();

        assertTrue(secondUpdated >= firstUpdated,
                "updated_at should advance (or stay equal) on subsequent upsert");
    }

    // ── 280-char body persists correctly ─────────────────────────────────────

    @Test
    void upsert_exactly280CharBodyPersists() {
        String body = "a".repeat(280);
        repo.upsert(EntityType.ACTRESS, "1", body);

        String stored = repo.find(EntityType.ACTRESS, "1").get().body();
        assertEquals(280, stored.length());
        assertEquals(body, stored);
    }

    // ── NFC normalization preserved through round-trip ────────────────────────

    @Test
    void upsert_nfcNormalizedBodyRoundTrips() {
        // NFD form of "é" is e + combining accent (two code points).
        // After NFC normalization the stored value should be the single-codepoint form.
        String nfd = Normalizer.normalize("é", Normalizer.Form.NFD); // 2 chars
        String nfc = Normalizer.normalize("é", Normalizer.Form.NFC); // 1 char

        repo.upsert(EntityType.ACTRESS, "5", nfd);
        String stored = repo.find(EntityType.ACTRESS, "5").get().body();

        // The repo stores exactly what it receives — NFC normalization is the
        // service's responsibility. Verify the value round-trips unchanged.
        assertEquals(nfd, stored, "Repository should store and return body as-is");

        // Separately, an NFC body also round-trips correctly.
        repo.upsert(EntityType.ACTRESS, "6", nfc);
        assertEquals(nfc, repo.find(EntityType.ACTRESS, "6").get().body());
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesExistingNote() {
        repo.upsert(EntityType.ACTRESS, "10", "to be deleted");
        repo.delete(EntityType.ACTRESS, "10");

        assertTrue(repo.find(EntityType.ACTRESS, "10").isEmpty());
    }

    @Test
    void delete_isNoOpWhenNoteDoesNotExist() {
        assertDoesNotThrow(() -> repo.delete(EntityType.ACTRESS, "nonexistent"));
    }

    @Test
    void delete_doesNotCrossEntityTypes() {
        repo.upsert(EntityType.ACTRESS, "42", "actress note");

        repo.delete(EntityType.TITLE, "42"); // different type

        assertTrue(repo.find(EntityType.ACTRESS, "42").isPresent(),
                "delete on TITLE should not affect ACTRESS note with same id");
    }

    // ── findAllForType ────────────────────────────────────────────────────────

    @Test
    void findAllForType_returnsOnlyMatchingIds() {
        repo.upsert(EntityType.ACTRESS, "1", "note one");
        repo.upsert(EntityType.ACTRESS, "2", "note two");
        repo.upsert(EntityType.ACTRESS, "3", "note three");

        Map<String, Note> result = repo.findAllForType(EntityType.ACTRESS, List.of("1", "3", "99"));

        assertEquals(2, result.size());
        assertTrue(result.containsKey("1"));
        assertTrue(result.containsKey("3"));
        assertFalse(result.containsKey("99"), "id with no note should be absent");
        assertFalse(result.containsKey("2"),  "id not in query should be absent");
    }

    @Test
    void findAllForType_returnsEmptyMapForEmptyInput() {
        repo.upsert(EntityType.ACTRESS, "1", "note one");

        Map<String, Note> result = repo.findAllForType(EntityType.ACTRESS, List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void findAllForType_doesNotCrossEntityTypes() {
        repo.upsert(EntityType.ACTRESS, "AAA-001", "actress note");

        Map<String, Note> result = repo.findAllForType(EntityType.TITLE, List.of("AAA-001"));

        assertTrue(result.isEmpty(), "findAllForType(TITLE) must not return ACTRESS notes");
    }

    // ── sweepOrphans ─────────────────────────────────────────────────────────

    @Test
    void sweepOrphans_deletesActressNoteWhenActressDeleted() {
        // Insert a canonical actress and a note for her
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses(canonical_name, tier, first_seen_at) VALUES ('Test Actress','POPULAR','2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        repo.upsert(EntityType.ACTRESS, String.valueOf(actressId), "note for actress");

        assertEquals(0, repo.sweepOrphans(), "no orphans when actress exists");

        // Delete the actress
        jdbi.useHandle(h -> h.execute("DELETE FROM actresses WHERE id = ?", actressId));

        int swept = repo.sweepOrphans();
        assertEquals(1, swept);
        assertTrue(repo.find(EntityType.ACTRESS, String.valueOf(actressId)).isEmpty());
    }

    @Test
    void sweepOrphans_deletesTitleNoteWhenTitleDeleted() {
        long titleId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO titles(code, base_code, label, seq_num) VALUES ('ABP-001','ABP-001','ABP',1)")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        repo.upsert(EntityType.TITLE, "ABP-001", "note for title");

        assertEquals(0, repo.sweepOrphans());

        jdbi.useHandle(h -> h.execute("DELETE FROM titles WHERE id = ?", titleId));

        int swept = repo.sweepOrphans();
        assertEquals(1, swept);
        assertTrue(repo.find(EntityType.TITLE, "ABP-001").isEmpty());
    }

    @Test
    void sweepOrphans_doesNotDeleteCanonicalNotes() {
        long actressId = jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses(canonical_name, tier, first_seen_at) VALUES ('Keep Actress','POPULAR','2024-01-01')")
                        .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        repo.upsert(EntityType.ACTRESS, String.valueOf(actressId), "keep this note");

        int swept = repo.sweepOrphans();

        assertEquals(0, swept);
        assertTrue(repo.find(EntityType.ACTRESS, String.valueOf(actressId)).isPresent());
    }
}
