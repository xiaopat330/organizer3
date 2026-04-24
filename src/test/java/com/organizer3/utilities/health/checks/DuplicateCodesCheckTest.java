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

class DuplicateCodesCheckTest {

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private DuplicateCodesCheck check;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')");
        });
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        check = new DuplicateCodesCheck(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void reportsZeroWhenNoDuplicates() {
        long tid = titleRepo.save(title("ABP-001")).getId();
        locationRepo.save(location(tid, "vol-a", "queue"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total());
        assertEquals(0, result.rows().size());
    }

    @Test
    void detectsIntraVolumeDuplicates() {
        long tid = titleRepo.save(title("ABP-001")).getId();
        locationRepo.save(location(tid, "vol-a", "queue"));
        locationRepo.save(location(tid, "vol-a", "stars/superstar"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(1, result.total());
        assertEquals(1, result.rows().size());
        assertEquals("ABP-001", result.rows().get(0).label());
        assertTrue(result.rows().get(0).detail().contains("2 copies"));
    }

    /**
     * Rule 3 false-positive guard: cross-volume duplicates (same title on two NAS boxes)
     * are EXPECTED in a multi-NAS library and must NOT be flagged. Only intra-volume
     * duplicates indicate a misplaced copy that needs triage.
     */
    @Test
    void crossVolumeDuplicatesAreNotFlagged() {
        long tid = titleRepo.save(title("ABP-001")).getId();
        locationRepo.save(location(tid, "vol-a", "queue"));
        locationRepo.save(location(tid, "vol-b", "queue"));

        LibraryHealthCheck.CheckResult result = check.run();
        assertEquals(0, result.total(), "cross-volume duplicate must not be flagged");
    }

    private static Title title(String code) {
        String label = code.split("-")[0];
        return Title.builder().code(code).baseCode(label + "-00" + code.split("-")[1])
                .label(label).seqNum(1).build();
    }

    private static TitleLocation location(long titleId, String volumeId, String partitionId) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId(partitionId)
                .path(Path.of("/" + partitionId + "/item-" + volumeId + "-" + titleId))
                .lastSeenAt(LocalDate.now()).build();
    }
}
