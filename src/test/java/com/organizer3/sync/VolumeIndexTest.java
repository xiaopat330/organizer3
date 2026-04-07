package com.organizer3.sync;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VolumeIndex — in-memory snapshot of volume DB records.
 */
class VolumeIndexTest {

    @Test
    void emptyCreatesIndexWithEmptyLists() {
        VolumeIndex index = VolumeIndex.empty("a");
        assertEquals("a", index.volumeId());
        assertEquals(0, index.titleCount());
        assertEquals(0, index.actressCount());
        assertTrue(index.titles().isEmpty());
        assertTrue(index.actresses().isEmpty());
    }

    @Test
    void titleCountReturnsSizeOfTitlesList() {
        Title t1 = title("ABP-001");
        Title t2 = title("ABP-002");
        VolumeIndex index = new VolumeIndex("a", List.of(t1, t2), List.of());
        assertEquals(2, index.titleCount());
    }

    @Test
    void actressCountReturnsSizeOfActressesList() {
        Actress a = Actress.builder().id(1L).canonicalName("Aya Sazanami")
                .tier(Actress.Tier.LIBRARY).build();
        VolumeIndex index = new VolumeIndex("a", List.of(), List.of(a));
        assertEquals(1, index.actressCount());
    }

    @Test
    void volumeIdIsPreserved() {
        VolumeIndex index = new VolumeIndex("vol_b", List.of(), List.of());
        assertEquals("vol_b", index.volumeId());
    }

    @Test
    void titlesListIsPreserved() {
        Title t = title("SSIS-001");
        VolumeIndex index = new VolumeIndex("a", List.of(t), List.of());
        assertEquals(1, index.titles().size());
        assertEquals("SSIS-001", index.titles().get(0).getCode());
    }

    // --- Helpers ---

    private Title title(String code) {
        return Title.builder()
                .id(1L).code(code).baseCode(code).label(code.split("-")[0]).seqNum(1)
                .volumeId("a").partitionId("queue")
                .path(Path.of("/queue/" + code))
                .lastSeenAt(LocalDate.now())
                .build();
    }
}
