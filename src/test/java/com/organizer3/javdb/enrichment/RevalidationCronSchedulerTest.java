package com.organizer3.javdb.enrichment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests for RevalidationCronScheduler.tick() — the two-phase cron flow:
 * drain dirty queue, then run safety-net slice.
 */
class RevalidationCronSchedulerTest {

    private RevalidationService revalidationService;
    private RevalidationPendingRepository pendingRepo;
    private RevalidationCronScheduler scheduler;

    private static final RevalidationService.RevalidationSummary EMPTY_SUMMARY =
            new RevalidationService.RevalidationSummary(0, 0, 0, 0, 0);

    @BeforeEach
    void setUp() {
        revalidationService = mock(RevalidationService.class);
        pendingRepo = mock(RevalidationPendingRepository.class);
        scheduler = new RevalidationCronScheduler(revalidationService, pendingRepo, 10, 50);

        when(revalidationService.revalidateBatch(anyList())).thenReturn(EMPTY_SUMMARY);
        when(revalidationService.revalidateSafetyNetSlice(anyInt())).thenReturn(EMPTY_SUMMARY);
    }

    @Test
    void drainsPendingQueueBeforeRunningsSafetyNet() {
        // First drain returns exactly drainBatchSize items → loop continues.
        // Second drain returns empty → loop exits. Safety-net runs after.
        List<RevalidationPendingRepository.Pending> fullBatch = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            fullBatch.add(new RevalidationPendingRepository.Pending((long) i, "sync", "2026-01-01T00:00:00Z"));
        }
        when(pendingRepo.drainBatch(10))
                .thenReturn(fullBatch)
                .thenReturn(List.of());

        scheduler.tick();

        var order = inOrder(pendingRepo, revalidationService);
        order.verify(pendingRepo).drainBatch(10);
        order.verify(revalidationService).revalidateBatch(anyList());
        order.verify(pendingRepo).drainBatch(10);    // second drain → empty
        order.verify(revalidationService).revalidateSafetyNetSlice(50);
    }

    @Test
    void emptyQueueStillRunsSafetyNet() {
        when(pendingRepo.drainBatch(10)).thenReturn(List.of());

        scheduler.tick();

        verify(pendingRepo).drainBatch(10);
        verify(revalidationService, never()).revalidateBatch(anyList());
        verify(revalidationService).revalidateSafetyNetSlice(50);
    }

    @Test
    void throwingServiceDoesNotKillSchedulerOnNextTick() {
        when(pendingRepo.drainBatch(10)).thenReturn(List.of());
        when(revalidationService.revalidateSafetyNetSlice(50))
                .thenThrow(new RuntimeException("db error"))
                .thenReturn(EMPTY_SUMMARY);

        // First tick throws — must not propagate
        scheduler.tick();

        // Second tick must still execute normally
        scheduler.tick();

        verify(revalidationService, times(2)).revalidateSafetyNetSlice(50);
    }

    @Test
    void multipleDrainBatchesWhenQueueLargerThanBatchSize() {
        // Full batch of 10 returned twice, then empty — two drain rounds
        RevalidationPendingRepository.Pending p = new RevalidationPendingRepository.Pending(99L, "sync", "2026-01-01T00:00:00Z");
        List<RevalidationPendingRepository.Pending> fullBatch = List.of(p, p, p, p, p, p, p, p, p, p);
        when(pendingRepo.drainBatch(10))
                .thenReturn(fullBatch)
                .thenReturn(List.of(p, p))   // partial batch → queue exhausted
                .thenReturn(List.of());       // should not be called

        scheduler.tick();

        verify(pendingRepo, times(2)).drainBatch(10);
        verify(revalidationService, times(2)).revalidateBatch(any());
        verify(revalidationService).revalidateSafetyNetSlice(50);
    }
}
