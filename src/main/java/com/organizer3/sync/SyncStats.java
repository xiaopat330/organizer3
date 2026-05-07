package com.organizer3.sync;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Accumulates per-run sync statistics as scanning progresses.
 * One instance is created per {@link SyncOperation#execute} call and discarded after printing.
 */
public final class SyncStats {

    public int total;
    public int queue;
    public int attention;
    public int staleMarked;
    public int staleCleared;
    public int swept;
    private final Set<Long> actressIds = new LinkedHashSet<>();
    private final Set<Long> titleIds   = new LinkedHashSet<>();

    void addTitles(String partitionId, int n) {
        total += n;
        switch (partitionId) {
            case "queue"     -> queue     += n;
            case "attention" -> attention += n;
        }
    }

    void addActress(long id) {
        actressIds.add(id);
    }

    void addTitle(long id) {
        titleIds.add(id);
    }

    int actressCount() {
        return actressIds.size();
    }

    Set<Long> touchedActressIds() {
        return actressIds;
    }

    Set<Long> touchedTitleIds() {
        return titleIds;
    }

    /**
     * Merges all counters and id sets from {@code other} into this instance.
     * Used by {@link com.organizer3.utilities.task.volume.CoherentMultiVolumeSyncTask}
     * to accumulate stats across all per-volume scans.
     */
    public void merge(SyncStats other) {
        this.total        += other.total;
        this.queue        += other.queue;
        this.attention    += other.attention;
        this.staleMarked  += other.staleMarked;
        this.staleCleared += other.staleCleared;
        this.swept        += other.swept;
        this.actressIds.addAll(other.actressIds);
        this.titleIds.addAll(other.titleIds);
    }
}
