package com.organizer3.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SyncStats — per-run sync statistics accumulator.
 */
class SyncStatsTest {

    @Test
    void defaultValuesAreZero() {
        SyncStats stats = new SyncStats();
        assertEquals(0, stats.total);
        assertEquals(0, stats.queue);
        assertEquals(0, stats.attention);
        assertEquals(0, stats.actressCount());
    }

    @Test
    void addTitlesIncrementsTotal() {
        SyncStats stats = new SyncStats();
        stats.addTitles("library", 5);
        assertEquals(5, stats.total);
    }

    @Test
    void addTitlesForQueueIncrementsQueueCounter() {
        SyncStats stats = new SyncStats();
        stats.addTitles("queue", 3);
        assertEquals(3, stats.total);
        assertEquals(3, stats.queue);
        assertEquals(0, stats.attention);
    }

    @Test
    void addTitlesForAttentionIncrementsAttentionCounter() {
        SyncStats stats = new SyncStats();
        stats.addTitles("attention", 2);
        assertEquals(2, stats.total);
        assertEquals(0, stats.queue);
        assertEquals(2, stats.attention);
    }

    @Test
    void addTitlesForOtherPartitionOnlyIncrementTotal() {
        SyncStats stats = new SyncStats();
        stats.addTitles("library", 7);
        assertEquals(7, stats.total);
        assertEquals(0, stats.queue);
        assertEquals(0, stats.attention);
    }

    @Test
    void multipleCallsAccumulate() {
        SyncStats stats = new SyncStats();
        stats.addTitles("queue", 10);
        stats.addTitles("library", 5);
        stats.addTitles("attention", 2);
        assertEquals(17, stats.total);
        assertEquals(10, stats.queue);
        assertEquals(2, stats.attention);
    }

    @Test
    void addActressDeduplicates() {
        SyncStats stats = new SyncStats();
        stats.addActress(10L);
        stats.addActress(10L);
        stats.addActress(20L);
        assertEquals(2, stats.actressCount());
    }

    @Test
    void actressCountReturnsUniqueCount() {
        SyncStats stats = new SyncStats();
        stats.addActress(1L);
        stats.addActress(2L);
        stats.addActress(3L);
        assertEquals(3, stats.actressCount());
    }
}
