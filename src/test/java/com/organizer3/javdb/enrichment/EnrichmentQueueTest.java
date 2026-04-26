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

    private static final JavdbConfig CONFIG = new JavdbConfig(true, 1.0, 3, new int[]{1, 5, 30}, 5, null, null);

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
    void markAttemptFailed_atMaxAttempts_setsFailedStatus() {
        JavdbConfig tightConfig = new JavdbConfig(true, 1.0, 1, new int[]{1}, 5, null, null);
        EnrichmentQueue tightQueue = new EnrichmentQueue(
                Jdbi.create(connection), tightConfig);

        tightQueue.enqueueTitle(2L, 20L);
        EnrichmentJob job = tightQueue.claimNextJob().get();
        tightQueue.markAttemptFailed(job.id(), "permanent failure");

        assertEquals(0, tightQueue.countPending());
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
}
