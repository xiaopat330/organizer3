package com.organizer3.utilities.health.checks;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link StaleLocationsCheck}. The check runs the same SQL that
 * {@link com.organizer3.utilities.volume.StaleLocationsService} does, so both must honor
 * the DATE()-wrap fix for the 2026-04-23 type-mismatch bug.
 */
class StaleLocationsCheckTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private StaleLocationsCheck check;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        check = new StaleLocationsCheck(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    /**
     * Rule 3 false-positive guard: locations seen on the same DAY as the volume's
     * last_synced_at datetime must NOT be flagged. Without DATE() wrapping, SQLite
     * compares '2024-06-01' &lt; '2024-06-01T10:14:37' and returns TRUE (prefix match).
     */
    @Test
    void sameDaySyncDoesNotFlagLocationAsStale() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-c', 'conventional', '2024-06-01T10:14:37')"));
        long tid = titleRepo.save(title("ABP-001")).getId();
        locationRepo.save(location(tid, "vol-c", LocalDate.of(2024, 6, 1)));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total(), "same-day location must not be stale");
        assertEquals(0, result.rows().size(), "per-volume breakdown must be empty");
    }

    @Test
    void locationFromBeforeSyncDayIsFlagged() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-c', 'conventional', '2024-06-01T10:14:37')"));
        long tid = titleRepo.save(title("ABP-002")).getId();
        locationRepo.save(location(tid, "vol-c", LocalDate.of(2024, 5, 31)));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        assertEquals("vol-c", result.rows().get(0).id());
        assertTrue(result.rows().get(0).detail().contains("1 stale row"));
    }

    /**
     * Edge case: volumes with last_synced_at IS NULL (never synced) must be skipped —
     * otherwise every location would be flagged stale on first mount.
     */
    @Test
    void neverSyncedVolumeIsSkipped() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-new', 'conventional')"));
        long tid = titleRepo.save(title("ABP-003")).getId();
        locationRepo.save(location(tid, "vol-new", LocalDate.of(2020, 1, 1)));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
    }

    @Test
    void perVolumeBreakdownGroupsByVolume() {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-a', 'conventional', '2024-06-01T10:00:00')");
            h.execute("INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-b', 'conventional', '2024-06-01T10:00:00')");
        });
        long t1 = titleRepo.save(title("A-001")).getId();
        long t2 = titleRepo.save(title("A-002")).getId();
        long t3 = titleRepo.save(title("B-001")).getId();
        locationRepo.save(location(t1, "vol-a", LocalDate.of(2024, 5, 1)));
        locationRepo.save(location(t2, "vol-a", LocalDate.of(2024, 5, 2)));
        locationRepo.save(location(t3, "vol-b", LocalDate.of(2024, 5, 10)));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(3, result.total());
        assertEquals(2, result.rows().size());
        // Ordered by count desc — vol-a (2) before vol-b (1).
        assertEquals("vol-a", result.rows().get(0).id());
        assertEquals("vol-b", result.rows().get(1).id());
    }

    private static Title title(String code) {
        String label = code.split("-")[0];
        return Title.builder().code(code).baseCode(label + "-00" + code.split("-")[1])
                .label(label).seqNum(1).build();
    }

    private static TitleLocation location(long titleId, String volumeId, LocalDate seen) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId("queue")
                .path(Path.of("/queue/item")).lastSeenAt(seen).build();
    }
}
