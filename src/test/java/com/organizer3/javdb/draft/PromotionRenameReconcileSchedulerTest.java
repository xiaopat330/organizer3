package com.organizer3.javdb.draft;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PromotionRenameReconcileScheduler#runOnce()} — the single daemon tick that
 * drives both the rename and cover reconcilers, each guarded so one throwing never suppresses
 * the other.
 */
class PromotionRenameReconcileSchedulerTest {

    @Test
    void runOnce_invokesBothReconcilers() {
        PromotionFolderRenameReconciler rename = Mockito.mock(PromotionFolderRenameReconciler.class);
        PromotionCoverReconciler cover = Mockito.mock(PromotionCoverReconciler.class);

        PromotionRenameReconcileScheduler scheduler =
                new PromotionRenameReconcileScheduler(rename, cover, 600, 5000);

        scheduler.runOnce();

        verify(rename).reconcile(5000);
        verify(cover).reconcile(5000);
    }

    @Test
    void runOnce_renameThrow_doesNotSuppressCover() {
        PromotionFolderRenameReconciler rename = Mockito.mock(PromotionFolderRenameReconciler.class);
        PromotionCoverReconciler cover = Mockito.mock(PromotionCoverReconciler.class);
        when(rename.reconcile(anyInt())).thenThrow(new RuntimeException("rename boom"));

        PromotionRenameReconcileScheduler scheduler =
                new PromotionRenameReconcileScheduler(rename, cover, 600, 5000);

        scheduler.runOnce();  // must not throw

        verify(cover).reconcile(5000);
    }

    @Test
    void runOnce_coverThrow_doesNotEscape() {
        PromotionFolderRenameReconciler rename = Mockito.mock(PromotionFolderRenameReconciler.class);
        PromotionCoverReconciler cover = Mockito.mock(PromotionCoverReconciler.class);
        when(cover.reconcile(anyInt())).thenThrow(new RuntimeException("cover boom"));

        PromotionRenameReconcileScheduler scheduler =
                new PromotionRenameReconcileScheduler(rename, cover, 600, 5000);

        scheduler.runOnce();  // must not throw

        verify(rename).reconcile(5000);
    }

    @Test
    void runOnce_nullCoverReconciler_stillRunsRename() {
        PromotionFolderRenameReconciler rename = Mockito.mock(PromotionFolderRenameReconciler.class);

        // Back-compat ctor — no cover reconciler.
        PromotionRenameReconcileScheduler scheduler =
                new PromotionRenameReconcileScheduler(rename, 600, 5000);

        scheduler.runOnce();

        verify(rename).reconcile(5000);
    }
}
