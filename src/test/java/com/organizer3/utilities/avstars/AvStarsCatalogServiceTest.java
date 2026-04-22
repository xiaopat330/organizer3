package com.organizer3.utilities.avstars;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvStarsCatalogServiceTest {

    private AvActressRepository actressRepo;
    private AvVideoRepository videoRepo;
    private AvStarsCatalogService svc;

    @BeforeEach
    void setup() {
        actressRepo = mock(AvActressRepository.class);
        videoRepo = mock(AvVideoRepository.class);
        svc = new AvStarsCatalogService(actressRepo, videoRepo);
    }

    @Test
    void filterAllExcludesRejected() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(
                actress(1, "Alice", false, false, false, null),
                actress(2, "Bob",   false, false, true,  null),   // rejected
                actress(3, "Carol", true,  false, false, "iafd-1")));
        var rows = svc.list(AvStarsCatalogService.Filter.ALL, AvStarsCatalogService.Sort.VIDEO_COUNT_DESC);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.id() == 2));
    }

    @Test
    void filterUnresolvedReturnsOnlyActressesWithoutIafdId() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(
                actress(1, "Alice", false, false, false, null),
                actress(2, "Bob",   false, false, false, "iafd-1"),
                actress(3, "Carol", false, false, false, "")));
        var rows = svc.list(AvStarsCatalogService.Filter.UNRESOLVED, AvStarsCatalogService.Sort.VIDEO_COUNT_DESC);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.id() == 2));
    }

    @Test
    void countsIgnoreRejected() {
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(
                actress(1, "Alice", true,  false, false, "iafd-1"),
                actress(2, "Bob",   false, false, true,  "iafd-2"),  // rejected; excluded
                actress(3, "Carol", false, false, false, null)));
        var c = svc.counts();
        assertEquals(2, c.total());
        assertEquals(1, c.resolved());
        assertEquals(1, c.favorites());
    }

    @Test
    void techSummaryAggregatesByCodecAndResolution() {
        when(videoRepo.findByActress(1L)).thenReturn(List.of(
                video("h264", "1920x1080", 1000L),
                video("h264", "1920x1080", 2000L),
                video("hevc", "3840x2160", 5000L),
                video(null,   null,        100L)));
        var t = svc.techSummary(1L);
        assertNotNull(t);
        assertEquals(4, t.videoCount());
        assertEquals(8100L, t.totalBytes());
        assertEquals(2, t.byCodec().get("h264"));
        assertEquals(1, t.byCodec().get("hevc"));
        assertEquals(1, t.byCodec().get("unknown"));
        assertEquals(2, t.byResolution().get("1920x1080"));
    }

    @Test
    void techSummaryIsNullWhenNoVideos() {
        when(videoRepo.findByActress(999L)).thenReturn(List.of());
        assertNull(svc.techSummary(999L));
    }

    private static AvActress actress(long id, String stage, boolean fav, boolean book,
                                     boolean rejected, String iafdId) {
        return AvActress.builder()
                .id(id).volumeId("qnap_av").folderName(stage).stageName(stage)
                .favorite(fav).bookmark(book).rejected(rejected).iafdId(iafdId)
                .videoCount(10)
                .build();
    }

    private static AvVideo video(String codec, String resolution, Long sizeBytes) {
        return AvVideo.builder()
                .avActressId(1L).volumeId("qnap_av").relativePath("p/" + System.nanoTime() + ".mp4")
                .filename("x.mp4").codec(codec).resolution(resolution).sizeBytes(sizeBytes)
                .build();
    }
}
