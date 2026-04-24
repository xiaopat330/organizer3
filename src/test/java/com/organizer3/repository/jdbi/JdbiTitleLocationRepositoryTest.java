package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Destructive-operation tests for {@link JdbiTitleLocationRepository}. Rule-3 false-positive
 * guard: for every volume-scoped delete, the first test seeds data on OTHER volumes and
 * asserts it survives. That catches the failure mode where a bad predicate (or bad volumeId
 * binding) wipes beyond its intended scope.
 */
class JdbiTitleLocationRepositoryTest {

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;

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
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // --- deleteByVolume ---

    /**
     * Rule 3 false-positive guard. A sync of vol-a must not touch vol-b rows. Run this test
     * FIRST for this method — catches the "wrong predicate wiped everything" failure mode
     * before verifying the happy-path delete works.
     */
    @Test
    void deleteByVolumeDoesNotTouchOtherVolumes() {
        long t1 = titleRepo.save(title("A-001")).getId();
        long t2 = titleRepo.save(title("B-001")).getId();
        locationRepo.save(location(t1, "vol-a", "queue"));
        locationRepo.save(location(t2, "vol-b", "queue"));

        locationRepo.deleteByVolume("vol-a");

        assertEquals(0, locationRepo.findByVolume("vol-a").size());
        assertEquals(1, locationRepo.findByVolume("vol-b").size(),
                "Other volume's locations must survive");
    }

    @Test
    void deleteByVolumeRemovesAllRowsOnTheVolume() {
        long t1 = titleRepo.save(title("A-001")).getId();
        long t2 = titleRepo.save(title("A-002")).getId();
        locationRepo.save(location(t1, "vol-a", "queue"));
        locationRepo.save(location(t2, "vol-a", "stars/superstar"));

        locationRepo.deleteByVolume("vol-a");

        assertEquals(0, locationRepo.findByVolume("vol-a").size());
    }

    /**
     * Defensive: a null volumeId must not wipe the table. In SQLite,
     * {@code volume_id = NULL} is never true, so this should be a no-op — but if a future
     * refactor switched to {@code IS NULL OR =} style, that'd be a disaster. Pin the
     * behavior with a test.
     */
    @Test
    void deleteByVolumeWithNullIdIsNoOp() {
        long tid = titleRepo.save(title("A-001")).getId();
        locationRepo.save(location(tid, "vol-a", "queue"));

        locationRepo.deleteByVolume(null);

        assertEquals(1, locationRepo.findByVolume("vol-a").size());
    }

    // --- deleteByVolumeAndPartition ---

    @Test
    void deleteByVolumeAndPartitionDoesNotTouchOtherPartitionsOrVolumes() {
        long t1 = titleRepo.save(title("A-001")).getId();
        long t2 = titleRepo.save(title("A-002")).getId();
        long t3 = titleRepo.save(title("B-001")).getId();
        locationRepo.save(location(t1, "vol-a", "queue"));
        locationRepo.save(location(t2, "vol-a", "stars/superstar"));
        locationRepo.save(location(t3, "vol-b", "queue"));

        locationRepo.deleteByVolumeAndPartition("vol-a", "queue");

        List<TitleLocation> aLeft = locationRepo.findByVolume("vol-a");
        assertEquals(1, aLeft.size(), "only queue partition should be gone on vol-a");
        assertEquals("stars/superstar", aLeft.get(0).getPartitionId());
        assertEquals(1, locationRepo.findByVolume("vol-b").size(), "vol-b must be untouched");
    }

    // --- deleteById ---

    @Test
    void deleteByIdRemovesOneRow() {
        long tid = titleRepo.save(title("A-001")).getId();
        TitleLocation saved = locationRepo.save(location(tid, "vol-a", "queue"));

        locationRepo.deleteById(saved.getId());

        assertEquals(0, locationRepo.findByVolume("vol-a").size());
    }

    @Test
    void deleteByIdWithUnknownIdIsNoOp() {
        long tid = titleRepo.save(title("A-001")).getId();
        locationRepo.save(location(tid, "vol-a", "queue"));

        locationRepo.deleteById(99_999L);

        assertEquals(1, locationRepo.findByVolume("vol-a").size());
    }

    // --- helpers ---

    private static Title title(String code) {
        String label = code.split("-")[0];
        return Title.builder().code(code).baseCode(label + "-00" + code.split("-")[1])
                .label(label).seqNum(1).build();
    }

    private static TitleLocation location(long titleId, String volumeId, String partitionId) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId(partitionId)
                .path(Path.of("/" + partitionId + "/" + volumeId + "-" + titleId))
                .lastSeenAt(LocalDate.now()).build();
    }
}
