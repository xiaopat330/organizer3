package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvScreenshotQueueRow;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository.ActressProgress;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbiAvScreenshotQueueRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiAvScreenshotQueueRepository repo;
    private long actressId;
    private long video1Id;
    private long video2Id;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('qnap_av', 'avstars')");
            actressId = h.createUpdate("""
                    INSERT INTO av_actresses (volume_id, folder_name, stage_name, first_seen_at,
                        video_count, total_size_bytes)
                    VALUES ('qnap_av', 'Test Actress', 'Test Actress', '2024-01-01T00:00:00', 0, 0)
                    """).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            video1Id = h.createUpdate("""
                    INSERT INTO av_videos (av_actress_id, volume_id, relative_path, filename, last_seen_at)
                    VALUES (?, 'qnap_av', 'v1.mp4', 'v1.mp4', '2024-01-01T00:00:00')
                    """).bind(0, actressId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            video2Id = h.createUpdate("""
                    INSERT INTO av_videos (av_actress_id, volume_id, relative_path, filename, last_seen_at)
                    VALUES (?, 'qnap_av', 'v2.mp4', 'v2.mp4', '2024-01-01T00:00:00')
                    """).bind(0, actressId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
        });
        repo = new JdbiAvScreenshotQueueRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- enqueueIfAbsent ---

    @Test
    void enqueueInsertsNewRow() {
        assertTrue(repo.enqueueIfAbsent(actressId, video1Id));
        assertEquals(1, rowCount());
    }

    @Test
    void enqueueIsIdempotent() {
        assertTrue(repo.enqueueIfAbsent(actressId, video1Id));
        assertFalse(repo.enqueueIfAbsent(actressId, video1Id));
        assertEquals(1, rowCount());
    }

    @Test
    void enqueueCreatesRowWithPendingStatus() {
        repo.enqueueIfAbsent(actressId, video1Id);
        String status = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM av_screenshot_queue WHERE av_video_id = ?")
                .bind(0, video1Id).mapTo(String.class).one());
        assertEquals("PENDING", status);
    }

    // --- claimNextPending: atomic claim + FIFO ordering ---

    @Test
    void claimReturnsEmptyWhenQueueEmpty() {
        Optional<AvScreenshotQueueRow> claimed = repo.claimNextPending();
        assertTrue(claimed.isEmpty());
    }

    @Test
    void claimSetsStatusToInProgress() {
        repo.enqueueIfAbsent(actressId, video1Id);
        Optional<AvScreenshotQueueRow> claimed = repo.claimNextPending();
        assertTrue(claimed.isPresent());
        assertEquals("IN_PROGRESS", claimed.get().getStatus());
        assertNotNull(claimed.get().getStartedAt());
    }

    @Test
    void claimReturnsFifoOrder() throws InterruptedException {
        repo.enqueueIfAbsent(actressId, video1Id);
        Thread.sleep(2); // ensure distinct enqueued_at
        repo.enqueueIfAbsent(actressId, video2Id);

        Optional<AvScreenshotQueueRow> first = repo.claimNextPending();
        assertTrue(first.isPresent());
        assertEquals(video1Id, first.get().getAvVideoId());

        Optional<AvScreenshotQueueRow> second = repo.claimNextPending();
        assertTrue(second.isPresent());
        assertEquals(video2Id, second.get().getAvVideoId());
    }

    @Test
    void claimDoesNotReturnPausedRows() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.pauseActress(actressId);

        Optional<AvScreenshotQueueRow> claimed = repo.claimNextPending();
        assertTrue(claimed.isEmpty());
    }

    // --- markDone / markFailed ---

    @Test
    void markDoneSetsDoneStatus() {
        repo.enqueueIfAbsent(actressId, video1Id);
        AvScreenshotQueueRow row = repo.claimNextPending().orElseThrow();
        repo.markDone(row.getId());

        String status = statusOf(row.getId());
        assertEquals("DONE", status);
    }

    @Test
    void markFailedSetsFailedStatusWithError() {
        repo.enqueueIfAbsent(actressId, video1Id);
        AvScreenshotQueueRow row = repo.claimNextPending().orElseThrow();
        repo.markFailed(row.getId(), "timeout after 120s");

        String status = statusOf(row.getId());
        assertEquals("FAILED", status);
        String error = jdbi.withHandle(h -> h.createQuery(
                "SELECT error FROM av_screenshot_queue WHERE id = ?")
                .bind(0, row.getId()).mapTo(String.class).one());
        assertEquals("timeout after 120s", error);
    }

    // --- resetOrphanedInFlightJobs ---

    @Test
    void resetOrphanedJobsResetsPendingInProgressRows() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.claimNextPending(); // puts it IN_PROGRESS
        // Simulate crash — row stays IN_PROGRESS
        int reset = repo.resetOrphanedInFlightJobs();
        assertEquals(1, reset);

        String status = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM av_screenshot_queue WHERE av_video_id = ?")
                .bind(0, video1Id).mapTo(String.class).one());
        assertEquals("PENDING", status);
    }

    @Test
    void resetOrphanedJobsDoesNotTouchOtherStatuses() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.enqueueIfAbsent(actressId, video2Id);
        AvScreenshotQueueRow row = repo.claimNextPending().orElseThrow();
        repo.markDone(row.getId());

        int reset = repo.resetOrphanedInFlightJobs();
        assertEquals(0, reset);
    }

    // --- pauseActress / resumeActress / clearForActress ---

    @Test
    void pauseActressTransitionsPendingToPaused() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.enqueueIfAbsent(actressId, video2Id);
        int paused = repo.pauseActress(actressId);
        assertEquals(2, paused);

        assertEquals(0L, countByStatus("PENDING"));
        assertEquals(2L, countByStatus("PAUSED"));
    }

    @Test
    void pauseActressDoesNotTouchInProgressRow() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.claimNextPending(); // IN_PROGRESS
        int paused = repo.pauseActress(actressId);
        assertEquals(0, paused); // no PENDING rows to pause
        assertEquals(1L, countByStatus("IN_PROGRESS"));
    }

    @Test
    void resumeActressTransitionsPausedToPending() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.pauseActress(actressId);
        int resumed = repo.resumeActress(actressId);
        assertEquals(1, resumed);
        assertEquals(1L, countByStatus("PENDING"));
        assertEquals(0L, countByStatus("PAUSED"));
    }

    @Test
    void clearForActressDeletesPendingAndPausedRows() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.enqueueIfAbsent(actressId, video2Id);
        repo.pauseActress(actressId); // both PAUSED
        int removed = repo.clearForActress(actressId);
        assertEquals(2, removed);
        assertEquals(0, rowCount());
    }

    @Test
    void clearForActressLeavesInProgressRow() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.enqueueIfAbsent(actressId, video2Id);
        repo.claimNextPending(); // video1 IN_PROGRESS
        int removed = repo.clearForActress(actressId);
        assertEquals(1, removed); // only video2 PENDING deleted
        assertEquals(1, rowCount()); // IN_PROGRESS row survives
    }

    // --- progressForActress ---

    @Test
    void progressReturnsZerosForActressWithNoRows() {
        ActressProgress p = repo.progressForActress(actressId);
        assertEquals(0, p.pending());
        assertEquals(0, p.inProgress());
        assertEquals(0, p.paused());
        assertEquals(0, p.done());
        assertEquals(0, p.failed());
        assertEquals(0, p.total());
        assertNull(p.currentVideoId());
    }

    @Test
    void progressCountsAllStatuses() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.enqueueIfAbsent(actressId, video2Id);
        // video1 → IN_PROGRESS → DONE
        AvScreenshotQueueRow row = repo.claimNextPending().orElseThrow();
        repo.markDone(row.getId());
        // video2 → PENDING
        ActressProgress p = repo.progressForActress(actressId);
        assertEquals(1, p.pending());
        assertEquals(0, p.inProgress());
        assertEquals(1, p.done());
        assertEquals(2, p.total());
        assertNull(p.currentVideoId());
    }

    @Test
    void progressReturnsCurrentVideoIdWhenInProgress() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.claimNextPending();
        ActressProgress p = repo.progressForActress(actressId);
        assertEquals(1, p.inProgress());
        assertEquals(video1Id, p.currentVideoId());
    }

    // --- globalDepth ---

    @Test
    void globalDepthCountsPendingAndInProgressOnly() {
        repo.enqueueIfAbsent(actressId, video1Id);
        repo.enqueueIfAbsent(actressId, video2Id);
        AvScreenshotQueueRow row = repo.claimNextPending().orElseThrow();
        repo.markDone(row.getId());

        assertEquals(1, repo.globalDepth()); // only video2 PENDING
    }

    // --- helpers ---

    private int rowCount() {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM av_screenshot_queue")
                .mapTo(Integer.class).one());
    }

    private long countByStatus(String status) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM av_screenshot_queue WHERE status = ?")
                .bind(0, status).mapTo(Long.class).one());
    }

    private String statusOf(long id) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM av_screenshot_queue WHERE id = ?")
                .bind(0, id).mapTo(String.class).one());
    }
}
