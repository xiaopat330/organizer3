package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Destructive-operation tests for {@link JdbiTitleLocationRepository}. Rule-3 false-positive
 * guard: for every volume-scoped delete, the first test seeds data on OTHER volumes and
 * asserts it survives. That catches the failure mode where a bad predicate (or bad volumeId
 * binding) wipes beyond its intended scope.
 */
class JdbiTitleLocationRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiVideoRepository videoRepo;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')");
        });
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        videoRepo = new JdbiVideoRepository(jdbi);
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

    // --- findById ---

    @Test
    void findByIdReturnsRowWhenPresent() {
        long tid = titleRepo.save(title("A-001")).getId();
        TitleLocation saved = locationRepo.save(location(tid, "vol-a", "queue"));

        Optional<TitleLocation> found = locationRepo.findById(saved.getId());

        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
        assertEquals("vol-a", found.get().getVolumeId());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        Optional<TitleLocation> found = locationRepo.findById(99_999L);
        assertTrue(found.isEmpty());
    }

    @Test
    void findByIdIncludesStaleRows() {
        long tid = titleRepo.save(title("A-001")).getId();
        TitleLocation saved = locationRepo.save(location(tid, "vol-a", "queue"));
        // save() clears stale_since; stamp it directly with ISO-8601 so the mapper's Instant.parse() works.
        Jdbi jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute(
                "UPDATE title_locations SET stale_since = ? WHERE id = ?",
                Instant.now().minus(180, ChronoUnit.DAYS).toString(), saved.getId()));

        Optional<TitleLocation> found = locationRepo.findById(saved.getId());

        assertTrue(found.isPresent(), "findById must include stale rows");
        assertNotNull(found.get().getStaleSince());
    }

    // --- SQL-predicate regression: grace-boundary check for sweep-row ---

    /**
     * Grace-check SQL regression. A row whose stale_since is exactly (now - graceDays + 1 day)
     * is still inside the grace window and must NOT be returned by findStaleOlderThan.
     * This is the "false-positive guard" for the sweep-row drilldown endpoint.
     */
    @Test
    void findStaleOlderThan_doesNotReturnRowInsideGrace() {
        long tid = titleRepo.save(title("A-001")).getId();
        TitleLocation saved = locationRepo.save(location(tid, "vol-a", "queue"));
        Jdbi jdbi = Jdbi.create(connection);
        // stale_since = now - 89 days  →  still inside the 90-day grace window
        // Use ISO-8601 so the MAPPER's Instant.parse() does not throw.
        jdbi.useHandle(h -> h.execute(
                "UPDATE title_locations SET stale_since = ? WHERE id = ?",
                Instant.now().minus(89, ChronoUnit.DAYS).toString(), saved.getId()));

        List<TitleLocation> stale = locationRepo.findStaleOlderThan(90);

        assertTrue(stale.isEmpty(), "Row inside the grace window must NOT appear in findStaleOlderThan");
    }

    /**
     * Grace-check SQL regression — happy path: a row past grace (now - (graceDays + 1) days)
     * MUST be returned.
     */
    @Test
    void findStaleOlderThan_returnsRowPastGrace() {
        long tid = titleRepo.save(title("A-001")).getId();
        TitleLocation saved = locationRepo.save(location(tid, "vol-a", "queue"));
        Jdbi jdbi = Jdbi.create(connection);
        // stale_since = now - 91 days  →  past the 90-day grace window
        // Use ISO-8601 so the MAPPER's Instant.parse() does not throw.
        jdbi.useHandle(h -> h.execute(
                "UPDATE title_locations SET stale_since = ? WHERE id = ?",
                Instant.now().minus(91, ChronoUnit.DAYS).toString(), saved.getId()));

        List<TitleLocation> stale = locationRepo.findStaleOlderThan(90);

        assertEquals(1, stale.size(), "Row past grace must appear in findStaleOlderThan");
        assertEquals(saved.getId(), stale.get(0).getId());
    }

    // --- updatePathPartitionAndVideos ---

    @Test
    void updatePathPartitionAndVideos_updatesLocationPathAndPartition() {
        long tid = titleRepo.save(title("A-001")).getId();
        TitleLocation loc = locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/Foo (A-001)"))
                .lastSeenAt(LocalDate.now()).build());

        locationRepo.updatePathPartitionAndVideos(
                loc.getId(), tid, "vol-a",
                "/queue/Foo (A-001)", "/stars/minor/Foo/Foo (A-001)", "minor");

        TitleLocation updated = locationRepo.findById(loc.getId()).orElseThrow();
        assertEquals("/stars/minor/Foo/Foo (A-001)", updated.getPath().toString());
        assertEquals("minor", updated.getPartitionId());
    }

    @Test
    void updatePathPartitionAndVideos_rewritesVideoPathsUnderOldFolder() {
        long tid = titleRepo.save(title("A-001")).getId();
        TitleLocation loc = locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/Foo (A-001)"))
                .lastSeenAt(LocalDate.now()).build());

        Video v = videoRepo.save(Video.builder()
                .titleId(tid).volumeId("vol-a")
                .filename("A-001.mp4")
                .path(Path.of("/queue/Foo (A-001)/video/A-001.mp4"))
                .lastSeenAt(LocalDate.now()).build());

        locationRepo.updatePathPartitionAndVideos(
                loc.getId(), tid, "vol-a",
                "/queue/Foo (A-001)", "/stars/minor/Foo/Foo (A-001)", "minor");

        Video updated = videoRepo.findById(v.getId()).orElseThrow();
        assertEquals("/stars/minor/Foo/Foo (A-001)/video/A-001.mp4",
                updated.getPath().toString());
    }

    @Test
    void updatePathPartitionAndVideos_doesNotTouchVideoUnderDifferentFolder() {
        long tid = titleRepo.save(title("A-001")).getId();
        // Two location rows: queue (being moved) and archive (must not be touched)
        TitleLocation queueLoc = locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/Foo (A-001)"))
                .lastSeenAt(LocalDate.now()).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-a").partitionId("archive")
                .path(Path.of("/archive/Foo (A-001)"))
                .lastSeenAt(LocalDate.now()).build());

        // Video under the queue folder (will be rewritten)
        Video queueVideo = videoRepo.save(Video.builder()
                .titleId(tid).volumeId("vol-a")
                .filename("A-001.mp4")
                .path(Path.of("/queue/Foo (A-001)/video/A-001.mp4"))
                .lastSeenAt(LocalDate.now()).build());

        // Video under the archive folder (must NOT be rewritten)
        Video archiveVideo = videoRepo.save(Video.builder()
                .titleId(tid).volumeId("vol-a")
                .filename("A-001.mp4")
                .path(Path.of("/archive/Foo (A-001)/video/A-001.mp4"))
                .lastSeenAt(LocalDate.now()).build());

        locationRepo.updatePathPartitionAndVideos(
                queueLoc.getId(), tid, "vol-a",
                "/queue/Foo (A-001)", "/stars/minor/Foo/Foo (A-001)", "minor");

        assertEquals("/stars/minor/Foo/Foo (A-001)/video/A-001.mp4",
                videoRepo.findById(queueVideo.getId()).orElseThrow().getPath().toString(),
                "queue video must be rewritten");
        assertEquals("/archive/Foo (A-001)/video/A-001.mp4",
                videoRepo.findById(archiveVideo.getId()).orElseThrow().getPath().toString(),
                "archive video must NOT be touched");
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
