package com.organizer3.sync;

import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for IndexLoader — builds a VolumeIndex from DB state.
 */
class IndexLoaderTest {

    private TitleRepository titleRepo;
    private ActressRepository actressRepo;
    private IndexLoader loader;

    @BeforeEach
    void setUp() {
        titleRepo = mock(TitleRepository.class);
        actressRepo = mock(ActressRepository.class);
        loader = new IndexLoader(titleRepo, actressRepo);
    }

    @Test
    void emptyVolumeReturnsEmptyIndex() {
        when(titleRepo.findByVolume("a")).thenReturn(List.of());

        VolumeIndex index = loader.load("a");

        assertEquals("a", index.volumeId());
        assertEquals(0, index.titleCount());
        assertEquals(0, index.actressCount());
    }

    @Test
    void loadsTitlesAndResolveActresses() {
        Title t1 = title("ABP-001", 10L);
        Title t2 = title("ABP-002", 10L);
        Title t3 = title("SSIS-001", 20L);
        when(titleRepo.findByVolume("a")).thenReturn(List.of(t1, t2, t3));

        Actress aya = Actress.builder().id(10L).canonicalName("Aya Sazanami")
                .tier(Actress.Tier.LIBRARY).build();
        Actress hibiki = Actress.builder().id(20L).canonicalName("Hibiki Otsuki")
                .tier(Actress.Tier.GODDESS).build();
        when(actressRepo.findById(10L)).thenReturn(Optional.of(aya));
        when(actressRepo.findById(20L)).thenReturn(Optional.of(hibiki));

        VolumeIndex index = loader.load("a");

        assertEquals(3, index.titleCount());
        assertEquals(2, index.actressCount());
        // Actresses should be sorted by name
        assertEquals("Aya Sazanami", index.actresses().get(0).getCanonicalName());
        assertEquals("Hibiki Otsuki", index.actresses().get(1).getCanonicalName());
    }

    @Test
    void titlesWithNullActressIdAreIgnoredForActressLookup() {
        Title attributed = title("ABP-001", 10L);
        Title unattributed = title("XYZ-001", null);
        when(titleRepo.findByVolume("a")).thenReturn(List.of(attributed, unattributed));

        Actress aya = Actress.builder().id(10L).canonicalName("Aya Sazanami")
                .tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.findById(10L)).thenReturn(Optional.of(aya));

        VolumeIndex index = loader.load("a");

        assertEquals(2, index.titleCount());
        assertEquals(1, index.actressCount());
        // Should only look up the one non-null actress id
        verify(actressRepo, times(1)).findById(anyLong());
    }

    @Test
    void deduplicatesActressIds() {
        // Two titles with the same actress — should only resolve once
        Title t1 = title("ABP-001", 10L);
        Title t2 = title("ABP-002", 10L);
        when(titleRepo.findByVolume("a")).thenReturn(List.of(t1, t2));

        Actress aya = Actress.builder().id(10L).canonicalName("Aya Sazanami")
                .tier(Actress.Tier.LIBRARY).build();
        when(actressRepo.findById(10L)).thenReturn(Optional.of(aya));

        VolumeIndex index = loader.load("a");

        assertEquals(1, index.actressCount());
        verify(actressRepo, times(1)).findById(10L);
    }

    // --- Helpers ---

    private Title title(String code, Long actressId) {
        return Title.builder()
                .id(1L).code(code).baseCode(code).label(code.split("-")[0]).seqNum(1)
                .actressId(actressId)
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("a").partitionId("queue")
                        .path(Path.of("/queue/" + code))
                        .lastSeenAt(LocalDate.now()).build()))
                .build();
    }
}
