package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TitleBrowseServiceTest {

    @Mock TitleRepository titleRepo;
    @Mock ActressRepository actressRepo;
    @Mock CoverPath coverPath;
    @Mock LabelRepository labelRepo;

    TitleBrowseService service;

    @BeforeEach
    void setUp() {
        service = new TitleBrowseService(titleRepo, actressRepo, coverPath, labelRepo);
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
        assertEquals("ABP-123", s.getCode());
        assertEquals("ABP-00123", s.getBaseCode());
        assertEquals("ABP", s.getLabel());
        assertEquals("Yui Hatano", s.getActressName());
        assertEquals("POPULAR", s.getActressTier());
        assertEquals("2024-01-15", s.getAddedDate());
        assertEquals("/covers/ABP/ABP-00123.jpg", s.getCoverUrl());
        assertEquals("/mnt/vol-a/stars/ABP-123", s.getLocation());
    }

    @Test
    void nullActressIdYieldsNullActressTier() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).getActressTier());
    }

    @Test
    void mapsPathAsLocation() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertEquals("/mnt/vol-a/stars/ABP-123", service.findRecent(0, 24).get(0).getLocation());
    }

    @Test
    void nullActressIdYieldsNullActressName() {
        Title title = title("SSIS-001", "SSIS-00001", "SSIS", null, LocalDate.of(2024, 6, 1));

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findRecent(0, 24);

        assertNull(results.get(0).getActressName());
        verifyNoInteractions(actressRepo);
    }

    @Test
    void missingCoverYieldsNullCoverUrl() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).getCoverUrl());
    }

    @Test
    void nullAddedDateYieldsNullAddedDate() {
        Title title = title("ABP-001", "ABP-00001", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertNull(service.findRecent(0, 24).get(0).getAddedDate());
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

        assertEquals("Airi Suzumura", results.get(0).getActressName());
        assertEquals("Airi Suzumura", results.get(1).getActressName());
        verify(actressRepo, times(1)).findById(5L); // only one DB call despite two titles
    }

    @Test
    void mapsActressIdToTitleSummary() {
        Title title = title("ABP-123", "ABP-00123", "ABP", 10L, null);
        Actress actress = Actress.builder().id(10L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.POPULAR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(actressRepo.findById(10L)).thenReturn(Optional.of(actress));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        assertEquals(10L, service.findRecent(0, 24).get(0).getActressId());
    }

    @Test
    void mapsCompanyAndLabelNameFromLabelRepo() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        when(labelRepo.findAllAsMap()).thenReturn(
                Map.of("ABP", new Label("ABP", "Absolutely Perfect", "Prestige")));

        TitleSummary s = service.findRecent(0, 24).get(0);
        assertEquals("Prestige", s.getCompanyName());
        assertEquals("Absolutely Perfect", s.getLabelName());
    }

    @Test
    void missingLabelEntryYieldsNullCompanyAndLabelName() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, null);

        when(titleRepo.findRecent(24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());
        // labelRepo returns empty map by default — no entry for ABP

        TitleSummary s = service.findRecent(0, 24).get(0);
        assertNull(s.getCompanyName());
        assertNull(s.getLabelName());
    }

    // --- findByVolumeQueue ---

    @Test
    void findByVolumeQueueDelegatesToVolumeAndPartitionRepo() {
        Title title = title("ABP-123", "ABP-00123", "ABP", null, LocalDate.of(2024, 3, 1));

        when(titleRepo.findByVolumeAndPartition("vol-a", "queue", 24, 0)).thenReturn(List.of(title));
        when(coverPath.find(title)).thenReturn(Optional.empty());

        List<TitleSummary> results = service.findByVolumeQueue("vol-a", 0, 24);

        assertEquals(1, results.size());
        assertEquals("ABP-123", results.get(0).getCode());
        verify(titleRepo).findByVolumeAndPartition("vol-a", "queue", 24, 0);
    }

    @Test
    void findByVolumeQueueInfersActressFromStarsCopyViaBaseCode() {
        // Queue title has a base_code but no actress_id (unorganized)
        Title queueTitle = Title.builder()
                .id(1L).code("ABP-123").baseCode("ABP-00123").label("ABP")
                .volumeId("vol-a").partitionId("queue")
                .path(Path.of("/mnt/vol-a/queue/ABP-123"))
                .lastSeenAt(LocalDate.of(2024, 1, 1))
                .build();
        Title starsTitle = title("ABP-123", "ABP-00123", "ABP", 7L, null); // same base_code, has actress
        Actress actress = Actress.builder().id(7L).canonicalName("Yui Hatano")
                .tier(Actress.Tier.POPULAR).favorite(false).firstSeenAt(LocalDate.of(2023, 1, 1)).build();

        when(titleRepo.findByVolumeAndPartition("vol-a", "queue", 24, 0)).thenReturn(List.of(queueTitle));
        when(titleRepo.findByBaseCode("ABP-00123")).thenReturn(List.of(queueTitle, starsTitle));
        when(actressRepo.findById(7L)).thenReturn(Optional.of(actress));

        List<TitleSummary> results = service.findByVolumeQueue("vol-a", 0, 24);

        assertEquals(1, results.size());
        assertEquals(7L, results.get(0).getActressId());
        assertEquals("Yui Hatano", results.get(0).getActressName());
    }

    @Test
    void findByVolumeQueueHandlesNullLabel() {
        Title title = titleWithNullLabel("NOCODE-001", null, null);

        when(titleRepo.findByVolumeAndPartition("unsorted", "queue", 24, 0)).thenReturn(List.of(title));

        List<TitleSummary> results = service.findByVolumeQueue("unsorted", 0, 24);

        assertEquals(1, results.size());
        assertNull(results.get(0).getCoverUrl());
        assertNull(results.get(0).getCompanyName());
        assertNull(results.get(0).getLabelName());
        verifyNoInteractions(coverPath);
    }

    // --- helpers ---

    private static Title title(String code, String baseCode, String label, Long actressId, LocalDate addedDate) {
        return Title.builder()
                .id(1L).code(code).baseCode(baseCode).label(label).seqNum(1)
                .volumeId("vol-a").partitionId("stars").actressId(actressId)
                .path(Path.of("/mnt/vol-a/stars/" + code))
                .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(addedDate)
                .build();
    }

    private static Title titleWithNullLabel(String code, Long actressId, LocalDate addedDate) {
        return Title.builder()
                .id(1L).code(code)
                .volumeId("unsorted").partitionId("queue").actressId(actressId)
                .path(Path.of("/mnt/unsorted/queue/" + code))
                .lastSeenAt(LocalDate.of(2024, 1, 1)).addedDate(addedDate)
                .build();
    }
}
