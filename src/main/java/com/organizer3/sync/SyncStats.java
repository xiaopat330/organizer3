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

    int actressCount() {
        return actressIds.size();
    }
}
