package com.organizer3.notes;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.jdbi.JdbiNoteRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrphanNoteFinderTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiNoteRepository noteRepo;
    private OrphanNoteFinder finder;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        noteRepo = new JdbiNoteRepository(jdbi);
        finder   = new OrphanNoteFinder(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long insertActress(String name) {
        return jdbi.withHandle(h ->
                h.createUpdate("INSERT INTO actresses(canonical_name, tier, first_seen_at) VALUES (:n,'POPULAR','2024-01-01')")
                        .bind("n", name)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one());
    }

    private void insertTitle(String code) {
        jdbi.useHandle(h ->
                h.createUpdate("INSERT INTO titles(code, base_code, label, seq_num) VALUES (:c,:c,'TEST',1)")
                        .bind("c", code)
                        .execute());
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_returnsEmptyWhenNoNotes() {
        assertTrue(finder.findAll().isEmpty());
    }

    @Test
    void findAll_returnsEmptyWhenAllNotesHaveCanonicalActress() {
        long id = insertActress("Active Actress");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(id), "keep me");

        assertTrue(finder.findAll().isEmpty(),
                "Note with existing actress must not be flagged as orphan");
    }

    @Test
    void findAll_returnsEmptyWhenAllNotesHaveCanonicalTitle() {
        insertTitle("ABP-001");
        noteRepo.upsert(EntityType.TITLE, "ABP-001", "keep me too");

        assertTrue(finder.findAll().isEmpty(),
                "Note with existing title must not be flagged as orphan");
    }

    @Test
    void findAll_returnsOrphanWhenActressDeleted() {
        long id = insertActress("Ghost Actress");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(id), "ghost note");

        // Delete the actress
        jdbi.useHandle(h -> h.execute("DELETE FROM actresses WHERE id = ?", id));

        List<OrphanNoteFinder.OrphanNote> orphans = finder.findAll();
        assertEquals(1, orphans.size());
        assertEquals(EntityType.ACTRESS, orphans.get(0).entityType());
        assertEquals(String.valueOf(id), orphans.get(0).entityId());
        assertEquals("ghost note", orphans.get(0).body());
    }

    @Test
    void findAll_returnsOrphanWhenTitleDeleted() {
        insertTitle("GHOST-001");
        noteRepo.upsert(EntityType.TITLE, "GHOST-001", "ghost title note");

        jdbi.useHandle(h -> h.execute("DELETE FROM titles WHERE code = 'GHOST-001'"));

        List<OrphanNoteFinder.OrphanNote> orphans = finder.findAll();
        assertEquals(1, orphans.size());
        assertEquals(EntityType.TITLE, orphans.get(0).entityType());
        assertEquals("GHOST-001", orphans.get(0).entityId());
    }

    @Test
    void findAll_returnsOnlyOrphans_notCanonical() {
        long aliveId = insertActress("Alive Actress");
        long deadId  = insertActress("Dead Actress");

        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(aliveId), "alive note");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(deadId),  "dead note");

        jdbi.useHandle(h -> h.execute("DELETE FROM actresses WHERE id = ?", deadId));

        List<OrphanNoteFinder.OrphanNote> orphans = finder.findAll();
        assertEquals(1, orphans.size());
        assertEquals(String.valueOf(deadId), orphans.get(0).entityId());
    }

    @Test
    void findAll_handlesMixOfActressAndTitleOrphans() {
        long deadActressId = insertActress("Dead For Test");
        insertTitle("DEAD-001");

        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(deadActressId), "actress orphan");
        noteRepo.upsert(EntityType.TITLE, "DEAD-001", "title orphan");

        // Delete both
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM actresses WHERE id = ?", deadActressId);
            h.execute("DELETE FROM titles WHERE code = 'DEAD-001'");
        });

        List<OrphanNoteFinder.OrphanNote> orphans = finder.findAll();
        assertEquals(2, orphans.size());
        // Results ordered by entity_type, entity_id
        assertTrue(orphans.stream().anyMatch(o -> o.entityType() == EntityType.ACTRESS));
        assertTrue(orphans.stream().anyMatch(o -> o.entityType() == EntityType.TITLE));
    }

    // ── pruneAll ──────────────────────────────────────────────────────────────

    @Test
    void pruneAll_returnsZeroWhenNoOrphans() {
        long id = insertActress("Keepable Actress");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(id), "keep");

        assertEquals(0, finder.pruneAll());
        // Note still exists
        assertTrue(noteRepo.find(EntityType.ACTRESS, String.valueOf(id)).isPresent());
    }

    @Test
    void pruneAll_deletesOrphanNotes() {
        long id = insertActress("Transient Actress");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(id), "transient note");

        jdbi.useHandle(h -> h.execute("DELETE FROM actresses WHERE id = ?", id));

        int deleted = finder.pruneAll();
        assertEquals(1, deleted);
        assertTrue(noteRepo.find(EntityType.ACTRESS, String.valueOf(id)).isEmpty());
    }

    @Test
    void pruneAll_leavesCanonicalNotesIntact() {
        long aliveId = insertActress("Survivor");
        long deadId  = insertActress("To Be Deleted");

        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(aliveId), "survivor note");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(deadId),  "orphan note");

        jdbi.useHandle(h -> h.execute("DELETE FROM actresses WHERE id = ?", deadId));

        int deleted = finder.pruneAll();
        assertEquals(1, deleted);

        // Canonical note must survive
        assertTrue(noteRepo.find(EntityType.ACTRESS, String.valueOf(aliveId)).isPresent(),
                "Canonical note must not be deleted");
        // Orphan is gone
        assertTrue(noteRepo.find(EntityType.ACTRESS, String.valueOf(deadId)).isEmpty());
    }

    @Test
    void pruneAll_isIdempotent() {
        long deadId = insertActress("Once Deleted");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(deadId), "once");

        jdbi.useHandle(h -> h.execute("DELETE FROM actresses WHERE id = ?", deadId));

        assertEquals(1, finder.pruneAll());
        // Second call is a no-op
        assertEquals(0, finder.pruneAll());
    }

    // ── findAll / pruneAll consistency ────────────────────────────────────────

    @Test
    void findAll_countMatchesPruneAll_count() {
        long d1 = insertActress("Dead One");
        long d2 = insertActress("Dead Two");
        insertTitle("D-001");

        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(d1), "n1");
        noteRepo.upsert(EntityType.ACTRESS, String.valueOf(d2), "n2");
        noteRepo.upsert(EntityType.TITLE, "D-001", "n3");

        jdbi.useHandle(h -> {
            h.execute("DELETE FROM actresses WHERE id = ?", d1);
            h.execute("DELETE FROM actresses WHERE id = ?", d2);
            h.execute("DELETE FROM titles WHERE code = 'D-001'");
        });

        int preview = finder.findAll().size();
        int deleted = finder.pruneAll();
        assertEquals(preview, deleted,
                "findAll count must equal pruneAll deleted count for the same set of orphans");
    }
}
