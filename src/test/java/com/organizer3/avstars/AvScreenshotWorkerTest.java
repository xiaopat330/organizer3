package com.organizer3.avstars;

import com.organizer3.avstars.model.AvScreenshotQueueRow;
import com.organizer3.avstars.repository.AvScreenshotQueueRepository;
import com.organizer3.media.StreamActivityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AvScreenshotWorker using a fast-tick worker (10ms loop/idle sleeps)
 * so tests don't wait for production 1s/5s intervals.
 */
class AvScreenshotWorkerTest {

    // Fast sleeps for testing — 10ms loop, 20ms idle/paused
    private static final long TEST_LOOP_SLEEP  = 10;
    private static final long TEST_IDLE_SLEEP  = 20;
    private static final long TEST_PAUSE_SLEEP = 20;

    private AvScreenshotQueueRepository queueRepo;
    private AvScreenshotService screenshotService;
    private StreamActivityTracker streamTracker;
    private AvScreenshotWorker worker;

    @BeforeEach
    void setUp() {
        queueRepo        = mock(AvScreenshotQueueRepository.class);
        screenshotService = mock(AvScreenshotService.class);
        streamTracker    = mock(StreamActivityTracker.class);
        worker = new AvScreenshotWorker(queueRepo, screenshotService, streamTracker,
                TEST_LOOP_SLEEP, TEST_IDLE_SLEEP, TEST_PAUSE_SLEEP);

        when(queueRepo.resetOrphanedInFlightJobs()).thenReturn(0);
        when(streamTracker.isPlaying(anyLong())).thenReturn(false);
    }

    // --- idle: empty queue ---

    @Test
    void workerMarksNothingWhenQueueEmpty() throws Exception {
        when(queueRepo.claimNextPending()).thenReturn(Optional.empty());

        worker.start();
        Thread.sleep(100);
        worker.stop();
        Thread.sleep(50);

        verify(screenshotService, never()).generateForVideo(anyLong());
        verify(queueRepo, never()).markDone(anyLong());
        verify(queueRepo, never()).markFailed(anyLong(), any());
    }

    // --- success path ---

    @Test
    void workerMarksDoneOnSuccessfulGeneration() throws Exception {
        AvScreenshotQueueRow job = row(1L, 10L, 100L);
        when(queueRepo.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        when(screenshotService.generateForVideo(10L)).thenReturn(List.of("url1", "url2"));

        worker.start();
        Thread.sleep(200);
        worker.stop();
        Thread.sleep(50);

        verify(queueRepo).markDone(1L);
        verify(queueRepo, never()).markFailed(anyLong(), any());
    }

    @Test
    void workerMarksFailedWhenNoFramesGenerated() throws Exception {
        AvScreenshotQueueRow job = row(1L, 10L, 100L);
        when(queueRepo.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        when(screenshotService.generateForVideo(10L)).thenReturn(List.of());

        worker.start();
        Thread.sleep(200);
        worker.stop();
        Thread.sleep(50);

        verify(queueRepo).markFailed(1L, "no frames generated");
    }

    // --- exception path ---

    @Test
    void workerMarksFailedOnServiceException() throws Exception {
        AvScreenshotQueueRow job = row(1L, 10L, 100L);
        when(queueRepo.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        when(screenshotService.generateForVideo(10L)).thenThrow(new RuntimeException("disk error"));

        worker.start();
        Thread.sleep(200);
        worker.stop();
        Thread.sleep(50);

        // ExecutionException wraps the real cause; worker unwraps and uses cause.getMessage()
        verify(queueRepo).markFailed(1L, "disk error");
    }

    // --- FIFO: two jobs processed in order ---

    @Test
    void workerProcessesJobsInFifoOrder() throws Exception {
        AvScreenshotQueueRow job1 = row(1L, 10L, 100L);
        AvScreenshotQueueRow job2 = row(2L, 11L, 100L);
        when(queueRepo.claimNextPending())
                .thenReturn(Optional.of(job1))
                .thenReturn(Optional.of(job2))
                .thenReturn(Optional.empty());
        when(screenshotService.generateForVideo(anyLong())).thenReturn(List.of("url"));

        worker.start();
        Thread.sleep(500); // job1 + loop sleep(10ms) + job2 — well within 500ms with fast sleeps
        worker.stop();
        Thread.sleep(50);

        var order = inOrder(queueRepo);
        order.verify(queueRepo).markDone(1L);
        order.verify(queueRepo).markDone(2L);
    }

    // --- playback gating ---

    @Test
    void workerDefersWhenStreamIsPlaying() throws Exception {
        // Stream active → worker should not claim a row
        when(streamTracker.isPlaying(anyLong())).thenReturn(true);
        when(queueRepo.claimNextPending()).thenReturn(Optional.empty());

        worker.start();
        Thread.sleep(100);
        worker.stop();
        Thread.sleep(50);

        verify(queueRepo, never()).claimNextPending();
    }

    @Test
    void workerResumesAfterStreamBecomesInactive() throws Exception {
        AvScreenshotQueueRow job = row(1L, 10L, 100L);
        // First few ticks: stream active; then inactive
        when(streamTracker.isPlaying(anyLong()))
                .thenReturn(true)
                .thenReturn(false);
        when(queueRepo.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        when(screenshotService.generateForVideo(10L)).thenReturn(List.of("url"));

        worker.start();
        // 20ms idle sleep (stream active) + processing time — well within 300ms
        Thread.sleep(300);
        worker.stop();
        Thread.sleep(50);

        verify(queueRepo, atLeastOnce()).claimNextPending();
    }

    // --- orphan reset on start ---

    @Test
    void startResetsOrphanedInFlightJobs() throws Exception {
        when(queueRepo.resetOrphanedInFlightJobs()).thenReturn(2);
        when(queueRepo.claimNextPending()).thenReturn(Optional.empty());

        worker.start();
        Thread.sleep(50);
        worker.stop();
        Thread.sleep(50);

        verify(queueRepo).resetOrphanedInFlightJobs();
    }

    // --- timeout: markFailed with timeout message ---

    @Test
    void workerMarksFailedWithTimeoutMessage() throws Exception {
        AvScreenshotQueueRow job = row(1L, 10L, 100L);
        when(queueRepo.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        when(screenshotService.generateForVideo(10L))
                .thenThrow(new RuntimeException("timeout simulation"));

        worker.start();
        Thread.sleep(200);
        worker.stop();
        Thread.sleep(50);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(queueRepo).markFailed(eq(1L), errorCaptor.capture());
        assertNotNull(errorCaptor.getValue());
    }

    // --- currentVideoId / currentActressId ---

    @Test
    void workerExposesCurrentVideoAndActressIdWhileProcessing() throws Exception {
        AvScreenshotQueueRow job = row(5L, 42L, 99L);
        when(queueRepo.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        when(screenshotService.generateForVideo(42L)).thenAnswer(inv -> {
            Thread.sleep(200);
            return List.of("url");
        });

        worker.start();
        Thread.sleep(80); // mid-processing (service sleeps 200ms)

        assertEquals(42L, worker.getCurrentVideoId());
        assertEquals(99L, worker.getCurrentActressId());

        Thread.sleep(300); // let it finish
        worker.stop();
        Thread.sleep(50);

        assertNull(worker.getCurrentVideoId());
        assertNull(worker.getCurrentActressId());
    }

    // --- helpers ---

    private static AvScreenshotQueueRow row(long id, long videoId, long actressId) {
        return AvScreenshotQueueRow.builder()
                .id(id)
                .avVideoId(videoId)
                .avActressId(actressId)
                .enqueuedAt("2024-01-01T00:00:00Z")
                .status("IN_PROGRESS")
                .build();
    }
}
