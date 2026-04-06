package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TitleBrowseServiceTest {

    @Mock TitleRepository titleRepo;
    @Mock ActressRepository actressRepo;
    @Mock CoverPath coverPath;

    TitleBrowseService service;

    @BeforeEach
    void setUp() {
        service = new TitleBrowseService(titleRepo, actressRepo, coverPath);
    }

    @Test
    void mapsActressNameAndCoverUrl() {
        Title title = title("ABP-123", "ABP-00123", "ABP", 10L, LocalDate.of(2024, 1, 15));
        Actress actress = Actress.builder().id(10L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.POPULAR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        Path coverFile = Path.of("/data/covers/ABP/ABP-00123.jpg");

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(actressRepo.findById(10L)).thenReturn(Optional.of(actress));
        when(coverPath.find(title)).thenReturn(Optional.of(coverFile));

        List<TitleSummary> results = service.findRecent(0, 24);

        assertEquals(1, results.size());
        TitleSummary s = results.get(0);
        assertEquals("ABP-123", s.code());
        assertEquals("ABP-00123", s.baseCode());
        assertEquals("ABP", s.label());
        assertEquals("Yui Hatano", s.actressName());
        assertEquals("2024-01-15", s.addedDate());
        assertEquals("/covers/ABP/ABP-00123.jpg", s.coverUrl());
    }

    @Test
    void nullActressIdYieldsNullActressName() {
        Title title = title("SSIS-001", "SSIS-00001", "SSIS", null, LocalDate.of(2024, 6, 1));

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findRecent(0, 24);

        assertNull(results.get(0).actressName());
        verifyNoInteractions(actressRepo);
    }

    @Test
    void missingCoverYieldsNullCoverUrl() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).coverUrl());
    }

    @Test
    void nullAddedDateYieldsNullAddedDate() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).addedDate());
    }

    @Test
    void limitsAreCappedAtMax() {
        when(titleRepo.findRecent(TitleBrowseService.MAX_LIMIT, 0)).thenReturn(List.of());

        service.findRecent(0, 9999);

        verify(titleRepo).findRecent(TitleBrowseService.MAX_LIMIT, 0);
    }

    @Test
    void deduplicatesActressLookups() {
        Actress actress = Actress.builder().id(5L).canonicalName("Airi Suzumura")
                .tier(Actress.Tier.MINOR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();
        Title t1 = title("ABP-001", "ABP-00001", "ABP", 5L, null);
        Title t2 = title("ABP-002", "ABP-00002", "ABP", 5L, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(t1, t2));
        when(actressRepo.findById(5L)).thenReturn(Optional.of(actress));
        when(coverPath.find(any())).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findRecent(0, 24);

        assertEquals("Airi Suzumura", results.get(0).actressName());
        assertEquals("Airi Suzumura", results.get(1).actressName());
        verify(actressRepo, times(1)).findById(5L); // only one DB call despite two titles
    }

    // --- helpers ---

    private static Title title(String code, String baseCode, String label, Long actressId, LocalDate addedDate) {
        return new Title(1L, code, baseCode, label, 1,
                "vol-a", "stars", actressId,
                Path.of("/mnt/vol-a/stars/" + code),
                LocalDate.of(2024, 1, 1), addedDate);
    }
}
