package com.organizer3.javdb.enrichment;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.JavdbConfig;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentQueueTest {

    private EnrichmentQueue queue;
    private Connection connection;

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null, null, null, 3, null, null);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        queue = new EnrichmentQueue(jdbi, CONFIG);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void enqueueTitle_insertsJob() {
        queue.enqueueTitle(1L, 10L);
        assertEquals(1, queue.countPending());
    }

    @Test
    void enqueueTitle_idempotent_noDuplicates() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(1L, 10L);
        assertEquals(1, queue.countPending());
    }

    @Test
    void claimNextJob_returnsJobAndSetsInFlight() {
        queue.enqueueTitle(1L, 10L);

        Optional<EnrichmentJob> job = queue.claimNextJob();
        assertTrue(job.isPresent());
        assertEquals("in_flight", job.get().status());
        assertEquals(EnrichmentJob.FETCH_TITLE, job.get().jobType());
        assertEquals(1L, job.get().targetId());
        assertEquals(10L, job.get().actressId());
    }

    @Test
    void claimNextJob_returnsEmptyWhenQueueEmpty() {
        assertTrue(queue.claimNextJob().isEmpty());
    }

    @Test
    void claimNextJob_respectsNextAttemptAt() {
        // Enqueue and then manually delay next_attempt_at into the future
        queue.enqueueTitle(1L, 10L);
        Optional<EnrichmentJob> job = queue.claimNextJob();
        assertTrue(job.isPresent());
        // Release it with a future retry time
        queue.markAttemptFailed(job.get().id(), "test error");

        // Should not claim the job because next_attempt_at is in the future
        assertTrue(queue.claimNextJob().isEmpty());
    }

    @Test
    void markDone_updatesStatus() {
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        queue.markDone(job.id());
        assertEquals(0, queue.countPending());
    }

    @Test
    void markAttemptFailed_withAttemptsRemaining_setsBackoffPending() {
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        queue.markAttemptFailed(job.id(), "network error");

        // Job should still count as pending (with future next_attempt_at)
        // countPending includes pending + in_flight
        assertEquals(1, queue.countPending());
    }

    @Test
    void markAttemptFailed_beyondBackoffSchedule_repeatsLastIntervalStaysPending() {
        // With a one-step backoff schedule, subsequent failures should keep repeating
        // the last interval rather than terminating as failed.
        JavdbConfig tightConfig = new JavdbConfig(true, 1.0, 1, new int[]{1}, 5, null, null, null, null, 3, null, null);
        EnrichmentQueue tightQueue = new EnrichmentQueue(Jdbi.create(connection), tightConfig);

        tightQueue.enqueueTitle(2L, 20L);
        EnrichmentJob job = tightQueue.claimNextJob().get();
        tightQueue.markAttemptFailed(job.id(), "transient error");

        // Item must stay in the pending pool (with a future next_attempt_at), not become failed.
        assertEquals(1, tightQueue.countPending());
    }

    @Test
    void markPermanentlyFailed_setsFailedWithoutConsumingAttempts() {
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        queue.markPermanentlyFailed(job.id(), "not_found");

        assertEquals(0, queue.countPending());
    }

    @Test
    void releaseToRetry_resetsTopendinglWithoutBurningAttempt() {
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        queue.releaseToRetry(job.id());

        Optional<EnrichmentJob> reclaimed = queue.claimNextJob();
        assertTrue(reclaimed.isPresent());
        assertEquals(0, reclaimed.get().attempts()); // no attempt burned
    }

    @Test
    void resetStuckInFlightJobs_resetsOldJobs() throws Exception {
        queue.enqueueTitle(1L, 10L);
        queue.claimNextJob(); // now in_flight

        // Manually backdated the updated_at to simulate a stuck job
        Jdbi jdbi = Jdbi.create(connection);
        String oldTime = Instant.now().minus(10, ChronoUnit.MINUTES).toString();
        jdbi.useHandle(h -> h.execute(
                "UPDATE javdb_enrichment_queue SET updated_at = ? WHERE status = 'in_flight'", oldTime));

        queue.resetStuckInFlightJobs(5);
        assertEquals(1, queue.countPending());
    }

    @Test
    void cancelForActress_cancelsPendingJobsForActress() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        queue.enqueueTitle(3L, 20L);

        queue.cancelForActress(10L);

        assertEquals(1, queue.countPending()); // only actress 20's job remains
    }

    @Test
    void cancelAll_cancelsAllPendingJobs() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 20L);
        queue.cancelAll();

        assertEquals(0, queue.countPending());
    }

    @Test
    void countPendingForActress_countsCorrectly() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        queue.enqueueTitle(3L, 20L);

        assertEquals(2, queue.countPendingForActress(10L));
        assertEquals(1, queue.countPendingForActress(20L));
    }

    @Test
    void claimNextJob_fifoByInsertionOrder() {
        // Items enqueued first must be claimed first, regardless of actress.
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 20L);
        queue.enqueueTitle(3L, 10L);

        EnrichmentJob first  = queue.claimNextJob().get();
        queue.markDone(first.id());
        EnrichmentJob second = queue.claimNextJob().get();
        queue.markDone(second.id());
        EnrichmentJob third  = queue.claimNextJob().get();

        assertEquals(1L, first.targetId());
        assertEquals(2L, second.targetId());
        assertEquals(3L, third.targetId());
    }

    @Test
    void enqueueActressProfile_insertsJob() {
        queue.enqueueActressProfile(10L);
        assertEquals(1, queue.countPendingForActress(10L));
    }

    @Test
    void enqueueActressProfile_idempotent() {
        queue.enqueueActressProfile(10L);
        queue.enqueueActressProfile(10L);
        assertEquals(1, queue.countPendingForActress(10L));
    }

    @Test
    void enqueueTitleForce_reenqueuesOverDoneJob() {
        queue.enqueueTitle(1L, 10L);
        Optional<EnrichmentJob> job = queue.claimNextJob();
        assertTrue(job.isPresent());
        queue.markDone(job.get().id());
        assertEquals(0, queue.countPending());

        queue.enqueueTitleForce(1L, 10L);
        assertEquals(1, queue.countPending());
    }

    @Test
    void enqueueTitleForce_noopWhenPendingOrInFlight() {
        queue.enqueueTitleForce(1L, 10L);
        queue.enqueueTitleForce(1L, 10L);
        assertEquals(1, queue.countPending());
    }

    @Test
    void enqueueActressProfileForce_reenqueuesOverDoneJob() {
        queue.enqueueActressProfile(10L);
        Optional<EnrichmentJob> job = queue.claimNextJob();
        assertTrue(job.isPresent());
        queue.markDone(job.get().id());
        assertEquals(0, queue.countPendingForActress(10L));

        queue.enqueueActressProfileForce(10L);
        assertEquals(1, queue.countPendingForActress(10L));
    }

    @Test
    void enqueueActressProfileForce_noopWhenPendingOrInFlight() {
        queue.enqueueActressProfileForce(10L);
        queue.enqueueActressProfileForce(10L);
        assertEquals(1, queue.countPendingForActress(10L));
    }

    // ── sort_order / prioritization tests ─────────────────────────────────

    @Test
    void enqueue_assignsIncreasingSort_order() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        queue.enqueueTitle(3L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        var orders = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue ORDER BY id")
                .mapTo(Long.class).list());
        assertEquals(3, orders.size());
        assertTrue(orders.get(0) < orders.get(1), "sort_order should increase");
        assertTrue(orders.get(1) < orders.get(2), "sort_order should increase");
    }

    @Test
    void claimNextJob_respectsSortOrder() {
        // Enqueue titles, then move title 3 to the front
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        queue.enqueueTitle(3L, 10L);

        // Find item id for title 3 and move to top
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 3")
                .mapTo(Long.class).one());
        queue.moveToTop(itemId);

        EnrichmentJob first = queue.claimNextJob().get();
        assertEquals(3L, first.targetId(), "title 3 should be claimed first after moveToTop");
    }

    @Test
    void moveToTop_placesItemFirst() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        queue.enqueueTitle(3L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 3")
                .mapTo(Long.class).one());
        queue.moveToTop(itemId);
        long topSortOrder = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", itemId).mapTo(Long.class).one());
        long minOthers = jdbi.withHandle(h -> h.createQuery(
                "SELECT MIN(sort_order) FROM javdb_enrichment_queue WHERE id != :id")
                .bind("id", itemId).mapTo(Long.class).one());
        assertTrue(topSortOrder < minOthers, "moved-to-top item should have lowest sort_order");
    }

    @Test
    void moveToBottom_placesItemLast() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        queue.enqueueTitle(3L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1")
                .mapTo(Long.class).one());
        queue.moveToBottom(itemId);
        long bottomOrder = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", itemId).mapTo(Long.class).one());
        long maxOthers = jdbi.withHandle(h -> h.createQuery(
                "SELECT MAX(sort_order) FROM javdb_enrichment_queue WHERE id != :id")
                .bind("id", itemId).mapTo(Long.class).one());
        assertTrue(bottomOrder > maxOthers, "moved-to-bottom item should have highest sort_order");
    }

    @Test
    void promoteItem_swapsWithPredecessor() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long id1 = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1").mapTo(Long.class).one());
        long id2 = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 2").mapTo(Long.class).one());
        long order1Before = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id1).mapTo(Long.class).one());
        long order2Before = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id2).mapTo(Long.class).one());

        queue.promoteItem(id2); // title 2 was after title 1; promote it

        long order1After = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id1).mapTo(Long.class).one());
        long order2After = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id2).mapTo(Long.class).one());
        assertEquals(order1Before, order2After, "title 2 should take title 1's original sort_order");
        assertEquals(order2Before, order1After, "title 1 should take title 2's original sort_order");
    }

    @Test
    void demoteItem_swapsWithSuccessor() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long id1 = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1").mapTo(Long.class).one());
        long id2 = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 2").mapTo(Long.class).one());
        long order1Before = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id1).mapTo(Long.class).one());
        long order2Before = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id2).mapTo(Long.class).one());

        queue.demoteItem(id1); // title 1 was before title 2; demote it

        long order1After = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id1).mapTo(Long.class).one());
        long order2After = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", id2).mapTo(Long.class).one());
        assertEquals(order2Before, order1After, "title 1 should take title 2's original sort_order");
        assertEquals(order1Before, order2After, "title 2 should take title 1's original sort_order");
    }

    @Test
    void pauseItem_changesPendingToPaused() {
        queue.enqueueTitle(1L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1").mapTo(Long.class).one());
        queue.pauseItem(itemId);
        String status = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", itemId).mapTo(String.class).one());
        assertEquals("paused", status);
    }

    @Test
    void pauseItem_noopOnInFlight() {
        queue.enqueueTitle(1L, 10L);
        queue.claimNextJob(); // now in_flight
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1").mapTo(Long.class).one());
        queue.pauseItem(itemId); // should be ignored
        String status = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", itemId).mapTo(String.class).one());
        assertEquals("in_flight", status, "in_flight items must not be paused");
    }

    @Test
    void pausedItem_notClaimed() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long firstItemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue ORDER BY sort_order LIMIT 1").mapTo(Long.class).one());
        queue.pauseItem(firstItemId);

        EnrichmentJob claimed = queue.claimNextJob().get();
        assertEquals(2L, claimed.targetId(), "paused item should be skipped; second item claimed");
    }

    @Test
    void resumeItem_restoresToPending() {
        queue.enqueueTitle(1L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1").mapTo(Long.class).one());
        long orderBefore = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", itemId).mapTo(Long.class).one());
        queue.pauseItem(itemId);
        queue.resumeItem(itemId);
        String status = jdbi.withHandle(h -> h.createQuery(
                "SELECT status FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", itemId).mapTo(String.class).one());
        long orderAfter = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", itemId).mapTo(Long.class).one());
        assertEquals("pending", status);
        assertEquals(orderBefore, orderAfter, "sort_order must be preserved through pause/resume");
    }

    // ── duplicate-prevention regression tests ─────────────────────────────

    @Test
    void enqueueTitle_afterFailed_replacesNotDuplicates() {
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        queue.markPermanentlyFailed(job.id(), "not found");

        // Re-enqueue — should replace the failed row, not add a second one
        queue.enqueueTitle(1L, 10L);

        assertEquals(1, queue.countPending(), "must have exactly one pending row, not two");
        Jdbi jdbi = Jdbi.create(connection);
        long total = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE target_id = 1 AND job_type = 'fetch_title'")
                .mapTo(Long.class).one());
        assertEquals(1, total, "failed row must be replaced, not kept alongside new pending row");
    }

    @Test
    void enqueueTitleForce_afterFailed_replacesNotDuplicates() {
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        queue.markPermanentlyFailed(job.id(), "parse error");

        queue.enqueueTitleForce(1L, 10L);

        assertEquals(1, queue.countPending());
        Jdbi jdbi = Jdbi.create(connection);
        long total = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE target_id = 1 AND job_type = 'fetch_title'")
                .mapTo(Long.class).one());
        assertEquals(1, total);
    }

    @Test
    void resetFailedForActress_withPreexistingPending_doesNotCreateDuplicate() {
        // Simulate the pre-fix scenario: a failed row AND a pending row both exist for the same title
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        queue.markPermanentlyFailed(job.id(), "error");
        // Manually insert a pending row to simulate the old duplicate state
        Jdbi jdbi = Jdbi.create(connection);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO javdb_enrichment_queue (job_type, target_id, actress_id, status, attempts, next_attempt_at, created_at, updated_at) " +
                "VALUES ('fetch_title', 1, 10, 'pending', 0, datetime('now'), datetime('now'), datetime('now'))"));

        queue.resetFailedForActress(10L);

        long pendingCount = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM javdb_enrichment_queue WHERE target_id = 1 AND status = 'pending'")
                .mapTo(Long.class).one());
        assertEquals(1, pendingCount, "failed row should be removed, not reset, when a pending row already exists");
    }

    @Test
    void cancelForActress_alsoCancelsPausedItems() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1").mapTo(Long.class).one());
        queue.pauseItem(itemId);
        queue.cancelForActress(10L);
        assertEquals(0, queue.countPendingForActress(10L));
        assertEquals(0, queue.countPaused());
    }

    @Test
    void requeueItem_makesPendingAndAppendsToQueue() {
        queue.enqueueTitle(1L, 10L);
        queue.enqueueTitle(2L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        // Fail the first item
        EnrichmentJob job = queue.claimNextJob().get();
        queue.markPermanentlyFailed(job.id(), "test error");
        // There's now one pending and one failed
        assertEquals(1, queue.countPendingForActress(10L));
        // Requeue the failed item
        queue.requeueItem(job.id());
        // Both should now be pending
        assertEquals(2, queue.countPendingForActress(10L));
        // The re-queued item should have been appended (higher sort_order than the remaining item)
        long requeued = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", job.id()).mapTo(Long.class).one());
        long other = jdbi.withHandle(h -> h.createQuery(
                "SELECT sort_order FROM javdb_enrichment_queue WHERE id != :id AND status = 'pending'")
                .bind("id", job.id()).mapTo(Long.class).one());
        assertTrue(requeued > other, "re-queued item should be appended after the existing pending item");
        // attempts and last_error should be cleared
        String lastError = jdbi.withHandle(h -> h.createQuery(
                "SELECT last_error FROM javdb_enrichment_queue WHERE id = :id")
                .bind("id", job.id()).mapTo(String.class).findOne().orElse(null));
        assertNull(lastError, "last_error should be cleared after requeue");
    }

    @Test
    void requeueItem_noopOnNonFailed() {
        queue.enqueueTitle(1L, 10L);
        Jdbi jdbi = Jdbi.create(connection);
        long itemId = jdbi.withHandle(h -> h.createQuery(
                "SELECT id FROM javdb_enrichment_queue WHERE target_id = 1").mapTo(Long.class).one());
        queue.requeueItem(itemId); // should be a no-op on a pending item
        assertEquals(1, queue.countPendingForActress(10L));
    }

    // ── Priority tests ────────────────────────────────────────────────────────

    @Test
    void enqueueWithoutPriority_defaultsToNormal() {
        queue.enqueueTitle(1L, 10L);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.NORMAL, job.priority());
    }

    @Test
    void enqueueTitle_withLowPriority_storesLow() {
        queue.enqueueTitle(EnrichmentJob.SOURCE_ACTRESS, 1L, 10L, Priority.LOW);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.LOW, job.priority());
    }

    @Test
    void enqueueTitle_withHighPriority_storesHigh() {
        queue.enqueueTitle(EnrichmentJob.SOURCE_ACTRESS, 1L, 10L, Priority.HIGH);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.HIGH, job.priority());
    }

    @Test
    void enqueueTitle_withUrgentPriority_storesUrgent() {
        queue.enqueueTitle(EnrichmentJob.SOURCE_ACTRESS, 1L, 10L, Priority.URGENT);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.URGENT, job.priority());
    }

    @Test
    void enqueueActressProfile_withNormalPriority_storesNormal() {
        queue.enqueueActressProfile(10L, Priority.NORMAL);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.NORMAL, job.priority());
    }

    @Test
    void enqueueActressProfile_withHighPriority_storesHigh() {
        queue.enqueueActressProfile(10L, Priority.HIGH);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.HIGH, job.priority());
    }

    @Test
    void enqueueTitleForce_withUrgentPriority_storesUrgent() {
        queue.enqueueTitleForce(1L, 10L, Priority.URGENT);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.URGENT, job.priority());
    }

    @Test
    void enqueueActressProfileForce_withHighPriority_storesHigh() {
        queue.enqueueActressProfileForce(10L, Priority.HIGH);
        EnrichmentJob job = queue.claimNextJob().get();
        assertEquals(Priority.HIGH, job.priority());
    }
}
