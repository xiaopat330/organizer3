package com.organizer3.media;

import com.organizer3.config.volume.BackgroundThumbnailConfig;
import com.organizer3.model.Video;
import com.organizer3.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Worker-level unit tests. We call {@link BackgroundThumbnailWorker#runOneCycle()}
 * directly instead of starting the real thread — that way no sleeping / scheduling
 * is involved and the test runs in milliseconds.
 */
class BackgroundThumbnailWorkerTest {

    private BackgroundThumbnailConfig cfg;
    private BackgroundThumbnailQueue queue;
    private ThumbnailService thumbnailService;
    private ThumbnailEvictor evictor;
    private VideoRepository videoRepo;
    private UserActivityTracker activity;
    private BackgroundThumbnailWorker worker;

    @BeforeEach
    void setUp() {
        // Zero quiet threshold so waitForQuiet returns immediately.
        cfg = new BackgroundThumbnailConfig(true, 0, 200, 300, 0, 300, 30);
        queue = mock(BackgroundThumbnailQueue.class);
        thumbnailService = mock(ThumbnailService.class);
        evictor = mock(ThumbnailEvictor.class);
        videoRepo = mock(VideoRepository.class);
        activity = new UserActivityTracker();
        // Bump is >0ms in the past by construction; quiet threshold of 0 still satisfies.
        worker = new BackgroundThumbnailWorker(
                cfg, queue, thumbnailService, evictor, videoRepo, activity);
        // Do NOT call start() — we drive cycles manually to avoid thread races.
        // The generation executor is wired up in the constructor.
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        worker.stop();
    }

    @Test
    void emptyQueueStillRunsEviction() throws Exception {
        when(queue.topCandidates(anyInt())).thenReturn(List.of());
        when(evictor.sweep(30)).thenReturn(2);

        worker.runOneCycle();

        verify(evictor).sweep(30);
        assertEquals(2, worker.getTotalEvicted());
    }

    @Test
    void successfulGenerationBumpsCounters() throws Exception {
        var cand = new BackgroundThumbnailQueue.Candidate(
                1L, 10L, "ABP-001", "v.mp4", 1500.0, null);
        when(queue.topCandidates(anyInt())).thenReturn(List.of(cand));
        when(thumbnailService.isComplete(anyString(), anyString(), anyLong())).thenReturn(false);
        when(videoRepo.findById(1L)).thenReturn(Optional.of(video(1L, 10L, "v.mp4")));
        when(thumbnailService.generateBlocking(eq("ABP-001"), any(Video.class), any())).thenReturn(true);

        worker.runOneCycle();

        verify(thumbnailService).generateBlocking(eq("ABP-001"), any(Video.class), any());
        assertEquals(1, worker.getTotalGenerated());
        assertEquals("ABP-001", worker.getLastGeneratedCode());
    }

    @Test
    void alreadyCompleteVideosAreSkipped() throws Exception {
        var cand = new BackgroundThumbnailQueue.Candidate(
                1L, 10L, "DONE-001", "v.mp4", 500.0, null);
        when(queue.topCandidates(anyInt())).thenReturn(List.of(cand));
        when(thumbnailService.isComplete("DONE-001", "v.mp4", 1L)).thenReturn(true);

        worker.runOneCycle();

        verify(thumbnailService, never()).generateBlocking(anyString(), any(), any());
        verify(videoRepo, never()).findById(anyLong());
    }

    @Test
    void videoGetsAddedToFailSetAfterTwoFailures() throws Exception {
        var cand = new BackgroundThumbnailQueue.Candidate(
                9L, 10L, "BAD-001", "v.mp4", 800.0, null);
        when(queue.topCandidates(anyInt())).thenReturn(List.of(cand));
        when(thumbnailService.isComplete(anyString(), anyString(), anyLong())).thenReturn(false);
        when(videoRepo.findById(9L)).thenReturn(Optional.of(video(9L, 10L, "v.mp4")));
        when(thumbnailService.generateBlocking(anyString(), any(), any()))
                .thenThrow(new IOException("boom"));

        worker.runOneCycle(); // fail #1
        worker.runOneCycle(); // fail #2 → fail-set

        // On the third cycle the candidate is filtered out before generateBlocking,
        // so call count stays at 2.
        worker.runOneCycle();
        verify(thumbnailService, times(2)).generateBlocking(anyString(), any(), any());
        assertEquals(0, worker.getTotalGenerated());
    }

    @Test
    void missingVideoRowSkipsGeneration() throws Exception {
        var cand = new BackgroundThumbnailQueue.Candidate(
                999L, 10L, "GONE-001", "v.mp4", 800.0, null);
        when(queue.topCandidates(anyInt())).thenReturn(List.of(cand));
        when(thumbnailService.isComplete(anyString(), anyString(), anyLong())).thenReturn(false);
        when(videoRepo.findById(999L)).thenReturn(Optional.empty());

        worker.runOneCycle();

        verify(thumbnailService, never()).generateBlocking(anyString(), any(), any());
    }

    private static Video video(long id, long titleId, String filename) {
        return Video.builder()
                .id(id).titleId(titleId).volumeId("vol-a")
                .filename(filename).path(Path.of("/x/" + filename))
                .lastSeenAt(LocalDate.now()).build();
    }
}
