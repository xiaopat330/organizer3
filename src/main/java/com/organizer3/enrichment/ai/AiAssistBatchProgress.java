package com.organizer3.enrichment.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the AI-assist sweeper's live batch-processing cursor.
 *
 * <p>The {@link BatchedEnsembleProcessor} drives this via its {@code ProgressSink}
 * (chunkStarted → {@link #startChunk}, passRow → {@link #setPass}, chunkEnded →
 * {@link #clear}). The dashboard reads {@link #snapshot()} to highlight the active
 * batch and show a single pass pill on the current row.
 */
public final class AiAssistBatchProgress {
    public record Snapshot(boolean active, List<Long> chunkRowIds, int pass,
                           Long currentRowId, String currentCode, String currentModel) {
        public static Snapshot idle() { return new Snapshot(false, List.of(), 0, null, null, null); }
    }
    private final AtomicReference<Snapshot> ref = new AtomicReference<>(Snapshot.idle());
    public void startChunk(List<Long> rowIds) {
        ref.set(new Snapshot(true, List.copyOf(rowIds), 0, null, null, null));
    }
    public void setPass(int pass, String model, long rowId, String code) {
        Snapshot s = ref.get();
        ref.set(new Snapshot(true, s.chunkRowIds(), pass, rowId, code, model));
    }
    public void clear() { ref.set(Snapshot.idle()); }
    public Snapshot snapshot() { return ref.get(); }
}
