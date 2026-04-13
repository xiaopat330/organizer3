package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiAvActressRepository using an in-memory SQLite database.
 */
class JdbiAvActressRepositoryTest {

    private JdbiAvActressRepository repo;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        // Insert a volumes row so FK constraints are satisfied
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('qnap_av', 'avstars')"));
        repo = new JdbiAvActressRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- upsert / findById ---

    @Test
    void upsertNewActressReturnsId() {
        long id = repo.upsert(actress("Anissa Kate"));
        assertTrue(id > 0);
    }

    @Test
    void findByIdReturnsInsertedActress() {
        long id = repo.upsert(actress("Anissa Kate"));
        Optional<AvActress> found = repo.findById(id);
        assertTrue(found.isPresent());
        assertEquals("Anissa Kate", found.get().getFolderName());
    }

    @Test
    void upsertOnConflictReturnsExistingId() {
        long first = repo.upsert(actress("Anissa Kate"));
        long second = repo.upsert(actress("Anissa Kate"));
        assertEquals(first, second);
    }

    @Test
    void upsertOnConflictDoesNotOverwriteData() {
        long id = repo.upsert(actress("Anissa Kate"));
        // A second upsert with the same key should not change the row
        repo.upsert(actress("Anissa Kate"));
        AvActress found = repo.findById(id).orElseThrow();
        assertEquals("Anissa Kate", found.getStageName());
    }

    // --- findByVolumeAndFolder ---

    @Test
    void findByVolumeAndFolderLocatesCorrectRow() {
        repo.upsert(actress("Anissa Kate"));
        repo.upsert(actress("Asa Akira"));
        Optional<AvActress> found = repo.findByVolumeAndFolder("qnap_av", "Anissa Kate");
        assertTrue(found.isPresent());
        assertEquals("Anissa Kate", found.get().getFolderName());
    }

    @Test
    void findByVolumeAndFolderReturnsEmptyWhenMissing() {
        Optional<AvActress> found = repo.findByVolumeAndFolder("qnap_av", "Nobody");
        assertTrue(found.isEmpty());
    }

    // --- findByVolume / findAllByVideoCountDesc ---

    @Test
    void findByVolumeReturnsSortedByName() {
        repo.upsert(actress("Zia Rose"));
        repo.upsert(actress("Anissa Kate"));
        List<AvActress> results = repo.findByVolume("qnap_av");
        assertEquals(2, results.size());
        assertEquals("Anissa Kate", results.get(0).getFolderName());
        assertEquals("Zia Rose", results.get(1).getFolderName());
    }

    @Test
    void findAllByVideoCountDescOrdersByCount() {
        long idA = repo.upsert(actress("Anissa Kate"));
        long idB = repo.upsert(actress("Asa Akira"));
        repo.updateCounts(idA, 5, 1000L);
        repo.updateCounts(idB, 12, 4000L);

        List<AvActress> results = repo.findAllByVideoCountDesc();
        assertEquals("Asa Akira", results.get(0).getFolderName());
        assertEquals("Anissa Kate", results.get(1).getFolderName());
    }

    // --- findFavorites ---

    @Test
    void findFavoritesReturnsFavoritedOnly() {
        long idA = repo.upsert(actress("Anissa Kate"));
        long idB = repo.upsert(actress("Asa Akira"));
        repo.updateCuration(idA, true, false, false, null, null);
        repo.updateCuration(idB, false, false, false, null, null);

        List<AvActress> favorites = repo.findFavorites();
        assertEquals(1, favorites.size());
        assertEquals("Anissa Kate", favorites.get(0).getFolderName());
    }

    @Test
    void findFavoritesReturnsEmptyWhenNone() {
        repo.upsert(actress("Anissa Kate"));
        List<AvActress> favorites = repo.findFavorites();
        assertTrue(favorites.isEmpty());
    }

    // --- updateCounts ---

    @Test
    void updateCountsPersistsValues() {
        long id = repo.upsert(actress("Anissa Kate"));
        repo.updateCounts(id, 42, 9_000_000_000L);
        AvActress found = repo.findById(id).orElseThrow();
        assertEquals(42, found.getVideoCount());
        assertEquals(9_000_000_000L, found.getTotalSizeBytes());
    }

    // --- updateLastScanned ---

    @Test
    void updateLastScannedPersistsTimestamp() {
        long id = repo.upsert(actress("Anissa Kate"));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        repo.updateLastScanned(id, now);
        AvActress found = repo.findById(id).orElseThrow();
        assertEquals(now, found.getLastScannedAt());
    }

    // --- updateCuration ---

    @Test
    void updateCurationPersistsAllFields() {
        long id = repo.upsert(actress("Anissa Kate"));
        repo.updateCuration(id, true, true, false, "S", "Great performer");
        AvActress found = repo.findById(id).orElseThrow();
        assertTrue(found.isFavorite());
        assertTrue(found.isBookmark());
        assertFalse(found.isRejected());
        assertEquals("S", found.getGrade());
        assertEquals("Great performer", found.getNotes());
    }

    // --- helpers ---

    private AvActress actress(String folderName) {
        return AvActress.builder()
                .volumeId("qnap_av")
                .folderName(folderName)
                .stageName(folderName)
                .firstSeenAt(LocalDateTime.now())
                .build();
    }
}
