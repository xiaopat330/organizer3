package com.organizer3.sync;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Accumulates per-run sync statistics as scanning progresses.
 * One instance is created per {@link SyncOperation#execute} call and discarded after printing.
 */
final class SyncStats {

    int total;
    int queue;
    int attention;
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
}
