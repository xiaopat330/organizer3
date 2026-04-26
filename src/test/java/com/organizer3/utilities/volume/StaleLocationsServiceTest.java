package com.organizer3.utilities.volume;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class StaleLocationsServiceTest {

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private Jdbi jdbi;
    private StaleLocationsService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        service = new StaleLocationsService(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void doesNotDeleteLocationsSeenOnSameDayAsSync() {
        // last_synced_at stored as datetime; last_seen_at stored as date.
        // SQLite string comparison '2024-06-01' < '2024-06-01T10:14:37' is true because
        // the shorter string is a prefix of the longer one. DATE() wrapping fixes this.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol', 'conventional', '2024-06-01T10:14:37')"));

        long tid = titleRepo.save(title("TEST-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol").partitionId("stars/superstar")
                .path(Path.of("/stars/superstar/Actress/TEST-001")).lastSeenAt(LocalDate.of(2024, 6, 1)).build());

        assertEquals(0, service.count("vol"), "count: same-day location must not be stale");
        assertEquals(0, service.preview("vol").size(), "preview: same-day location must not be stale");
        assertEquals(0, service.delete("vol"), "delete: same-day location must not be deleted");
    }

    @Test
    void deletesLocationsOlderThanSyncDay() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol', 'conventional', '2024-06-01T10:14:37')"));

        long tid = titleRepo.save(title("OLD-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol").partitionId("queue")
                .path(Path.of("/queue/OLD-001")).lastSeenAt(LocalDate.of(2024, 5, 31)).build());

        assertEquals(1, service.count("vol"));
        assertEquals(1, service.delete("vol"));
        assertEquals(0, service.count("vol"));
    }

    /**
     * Rule 4 cascade guard: if the predicate would wipe more than half of a volume's
     * locations, the delete must be refused and nothing touched. This is the direct
     * regression for the 2026-04-23 incident — if this guard had existed then, the bug
     * would have surfaced as a failed Clean action instead of a silent data loss.
     */
    @Test
    void refusesDeleteWhenCatastrophicFractionWouldBeWiped() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol', 'conventional', '2024-06-01T10:14:37')"));

        // Seed 100 locations — all older than sync day (every row would be "stale").
        // Threshold at 50% is 50; stale=100 > threshold=50 → must throw.
        for (int i = 0; i < 100; i++) {
            long tid = titleRepo.save(title("BAD-" + String.format("%03d", i))).getId();
            locationRepo.save(TitleLocation.builder()
                    .titleId(tid).volumeId("vol").partitionId("queue")
                    .path(Path.of("/queue/BAD-" + i))
                    .lastSeenAt(LocalDate.of(2024, 5, 1)).build());
        }

        com.organizer3.repository.CatastrophicDeleteException ex = assertThrows(
                com.organizer3.repository.CatastrophicDeleteException.class,
                () -> service.delete("vol"));
        assertEquals(100, ex.wouldDelete());
        assertEquals(100, ex.total());

        // Nothing was deleted — the transaction rolled back.
        assertEquals(100, service.count("vol"));
    }

    /**
     * Boundary: exactly half stale is allowed through (stale == threshold), since the
     * guard uses strict >. A catalog-curation edge case with many stale rows but not
     * "catastrophic" must still clean up.
     */
    @Test
    void allowsDeleteAtExactlyThreshold() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol', 'conventional', '2024-06-01T10:14:37')"));

        // 4 fresh + 4 stale. Threshold = 4. stale=4 > 4 is false → proceed.
        for (int i = 0; i < 4; i++) {
            long tid = titleRepo.save(title("OK-" + String.format("%03d", i))).getId();
            locationRepo.save(TitleLocation.builder()
                    .titleId(tid).volumeId("vol").partitionId("queue")
                    .path(Path.of("/queue/OK-" + i))
                    .lastSeenAt(LocalDate.of(2024, 6, 1)).build()); // same day — not stale
        }
        for (int i = 0; i < 4; i++) {
            long tid = titleRepo.save(title("OLD-" + String.format("%03d", i))).getId();
            locationRepo.save(TitleLocation.builder()
                    .titleId(tid).volumeId("vol").partitionId("queue")
                    .path(Path.of("/queue/OLD-" + i))
                    .lastSeenAt(LocalDate.of(2024, 5, 1)).build());
        }

        assertEquals(4, service.delete("vol"));
        assertEquals(0, service.count("vol"));
    }

    @Test
    void skipsVolumesNeverSynced() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol', 'conventional')"));

        long tid = titleRepo.save(title("X-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol").partitionId("queue")
                .path(Path.of("/queue/X-001")).lastSeenAt(LocalDate.of(2020, 1, 1)).build());

        assertEquals(0, service.count("vol"));
        assertEquals(0, service.delete("vol"));
    }

    private static Title title(String code) {
        return Title.builder()
                .code(code)
                .baseCode(code.replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .build();
    }
}
