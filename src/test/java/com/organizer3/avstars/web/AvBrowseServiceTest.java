package com.organizer3.avstars.web;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.avstars.repository.AvVideoTagRepository;
import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvBrowseServiceTest {

    @Mock AvActressRepository    actressRepo;
    @Mock AvVideoRepository      videoRepo;
    @Mock AvScreenshotRepository screenshotRepo;
    @Mock AvVideoTagRepository   videoTagRepo;

    AvBrowseService service;

    @BeforeEach
    void setUp() {
        AppConfig.reset();
        service = new AvBrowseService(actressRepo, videoRepo, screenshotRepo, videoTagRepo);
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    // ── getVideoDetail ────────────────────────────────────────────────────

    @Test
    void getVideoDetailReturnsEmptyWhenVideoNotFound() {
        when(videoRepo.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.getVideoDetail(99L).isEmpty());
    }

    @Test
    void getVideoDetailPopulatesFields() {
        var video   = video(1L, 42L, "old/video.mp4", "video.mp4");
        var actress = actress(42L, "Anissa Kate", "Anissa Kate");
        when(videoRepo.findById(1L)).thenReturn(Optional.of(video));
        when(actressRepo.findById(42L)).thenReturn(Optional.of(actress));

        var detail = service.getVideoDetail(1L).orElseThrow();

        assertEquals(1L,           detail.getId());
        assertEquals(42L,          detail.getActressId());
        assertEquals("Anissa Kate",detail.getActressStageName());
        assertEquals("Anissa Kate",detail.getActressFolderName());
        assertEquals("old/video.mp4", detail.getRelativePath());
        assertEquals("video.mp4",  detail.getFilename());
    }

    @Test
    void getVideoDetailHandlesMissingActressGracefully() {
        var video = video(1L, 42L, "video.mp4", "video.mp4");
        when(videoRepo.findById(1L)).thenReturn(Optional.of(video));
        when(actressRepo.findById(42L)).thenReturn(Optional.empty());

        var detail = service.getVideoDetail(1L).orElseThrow();
        assertNull(detail.getActressStageName());
        assertNull(detail.getActressFolderName());
        assertNull(detail.getSmbUrl()); // no folder name → no URL
    }

    // ── deriveSmbUrl ──────────────────────────────────────────────────────

    @Test
    void deriveSmbUrlConstructsCorrectUrl() {
        initConfigWithVolume("qnap_av", "//qnap2/AV/stars");
        var v = video(1L, 42L, "old/video.mp4", "video.mp4");

        String url = AvBrowseService.deriveSmbUrl(v, "Anissa Kate");

        assertEquals("smb://qnap2/AV/stars/Anissa%20Kate/old/video.mp4", url);
    }

    @Test
    void deriveSmbUrlEncodesSpecialCharactersInRelativePath() {
        initConfigWithVolume("qnap_av", "//qnap2/AV/stars");
        var v = video(1L, 42L, "file with spaces.mp4", "file with spaces.mp4");

        String url = AvBrowseService.deriveSmbUrl(v, "Simple");

        assertEquals("smb://qnap2/AV/stars/Simple/file%20with%20spaces.mp4", url);
    }

    @Test
    void deriveSmbUrlReturnsNullWhenVolumeNotFound() {
        initConfigWithVolume("qnap_av", "//qnap2/AV/stars");
        var v = AvVideo.builder()
                .id(2L).avActressId(42L).volumeId("other_vol")
                .relativePath("video.mp4").filename("video.mp4")
                .extension("mp4").build();

        assertNull(AvBrowseService.deriveSmbUrl(v, "Folder"));
    }

    @Test
    void deriveSmbUrlReturnsNullWhenFolderNameIsNull() {
        initConfigWithVolume("qnap_av", "//qnap2/AV/stars");
        var v = video(1L, 42L, "video.mp4", "video.mp4");
        assertNull(AvBrowseService.deriveSmbUrl(v, null));
    }

    @Test
    void deriveSmbUrlReturnsNullWhenAppConfigNotInitialized() {
        // AppConfig.reset() called in @BeforeEach — no initialize() here
        var v = video(1L, 42L, "video.mp4", "video.mp4");
        assertNull(AvBrowseService.deriveSmbUrl(v, "Folder"));
    }

    @Test
    void deriveSmbUrlEncodesHashAndBrackets() {
        initConfigWithVolume("qnap_av", "//qnap2/AV/stars");
        var v = AvVideo.builder()
                .id(1L).avActressId(42L).volumeId("qnap_av")
                .relativePath("file[hd].mp4").filename("file[hd].mp4")
                .extension("mp4").build();

        String url = AvBrowseService.deriveSmbUrl(v, "Folder");

        assertEquals("smb://qnap2/AV/stars/Folder/file%5Bhd%5D.mp4", url);
    }

    // ── findAll / findFavorites ───────────────────────────────────────────

    @Test
    void findAllMapsActressesWithTopTags() {
        var a = actress(1L, "Asa Akira", "Asa Akira");
        when(actressRepo.findAllByVideoCountDesc()).thenReturn(List.of(a));
        when(videoTagRepo.findTopTagSlugsForActress(1L, 5)).thenReturn(List.of("solo", "pov"));

        var out = service.findAll();

        assertEquals(1, out.size());
        assertEquals(1L, out.get(0).getId());
        assertEquals("Asa Akira", out.get(0).getStageName());
        assertEquals(List.of("solo", "pov"), out.get(0).getTopTags());
    }

    @Test
    void findFavoritesDelegatesToFavoritesRepo() {
        var a = actress(1L, "Fav", "Fav");
        when(actressRepo.findFavorites()).thenReturn(List.of(a));
        when(videoTagRepo.findTopTagSlugsForActress(1L, 5)).thenReturn(List.of());

        var out = service.findFavorites();

        assertEquals(1, out.size());
        verify(actressRepo).findFavorites();
        verify(actressRepo, never()).findAllByVideoCountDesc();
    }

    // ── getActressDetail ──────────────────────────────────────────────────

    @Test
    void getActressDetailReturnsEmptyWhenMissing() {
        when(actressRepo.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.getActressDetail(99L).isEmpty());
    }

    @Test
    void getActressDetailPopulatesFields() {
        var a = actress(1L, "Stage", "Folder");
        when(actressRepo.findById(1L)).thenReturn(Optional.of(a));

        var detail = service.getActressDetail(1L).orElseThrow();

        assertEquals("Stage", detail.getStageName());
        assertEquals("Folder", detail.getFolderName());
    }

    // ── findVideosForActress ──────────────────────────────────────────────

    @Test
    void findVideosForActressAttachesTagsAndThumbUrl() {
        var v = video(10L, 1L, "video.mp4", "video.mp4");
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v));
        when(videoTagRepo.findTagSlugsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of(10L, List.of("solo")));
        when(screenshotRepo.findFirstSeqByVideoIds(List.of(10L))).thenReturn(java.util.Map.of(10L, 3));
        when(screenshotRepo.findCountsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of(10L, 12));

        var out = service.findVideosForActress(1L);

        assertEquals(1, out.size());
        assertEquals("/api/av/screenshots/10/3", out.get(0).getFirstScreenshotUrl());
        assertEquals(12, out.get(0).getScreenshotCount());
        assertEquals(List.of("solo"), out.get(0).getTags());
    }

    @Test
    void findVideosForActressFallsBackToTagsJsonWhenNoCanonical() {
        var v = AvVideo.builder()
                .id(10L).avActressId(1L).volumeId("qnap_av")
                .relativePath("video.mp4").filename("video.mp4").extension("mp4")
                .tagsJson("[\"amateur\",\"raw\"]")
                .build();
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v));
        when(videoTagRepo.findTagSlugsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findFirstSeqByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findCountsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());

        var out = service.findVideosForActress(1L);

        assertEquals(List.of("amateur", "raw"), out.get(0).getTags());
        assertNull(out.get(0).getFirstScreenshotUrl());
        assertEquals(0, out.get(0).getScreenshotCount());
    }

    @Test
    void findVideosForActressHandlesMalformedTagsJson() {
        var v = AvVideo.builder()
                .id(10L).avActressId(1L).volumeId("qnap_av")
                .relativePath("video.mp4").filename("video.mp4").extension("mp4")
                .tagsJson("not-json")
                .build();
        when(videoRepo.findByActress(1L)).thenReturn(List.of(v));
        when(videoTagRepo.findTagSlugsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findFirstSeqByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findCountsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());

        var out = service.findVideosForActress(1L);

        // Malformed JSON leaves tags empty; does not throw
        assertEquals(List.of(), out.get(0).getTags());
    }

    // ── recordVisit / toggle actions ──────────────────────────────────────

    @Test
    void recordVisitCallsRepoAndReturnsSummary() {
        var a = actress(1L, "A", "A");
        when(actressRepo.findById(1L)).thenReturn(Optional.of(a));
        when(videoTagRepo.findTopTagSlugsForActress(1L, 5)).thenReturn(List.of());

        var out = service.recordVisit(1L);

        verify(actressRepo).recordVisit(1L);
        assertEquals(1L, out.getId());
    }

    @Test
    void recordVisitThrowsWhenActressMissing() {
        when(actressRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.recordVisit(99L));
    }

    @Test
    void toggleActressFavoriteCallsRepoAndReturnsDetail() {
        var a = actress(1L, "A", "A");
        when(actressRepo.findById(1L)).thenReturn(Optional.of(a));

        var detail = service.toggleActressFavorite(1L, true);

        verify(actressRepo).toggleFavorite(1L, true);
        assertEquals(1L, detail.getId());
    }

    @Test
    void toggleActressBookmarkCallsRepoAndReturnsDetail() {
        var a = actress(1L, "A", "A");
        when(actressRepo.findById(1L)).thenReturn(Optional.of(a));

        service.toggleActressBookmark(1L, false);

        verify(actressRepo).toggleBookmark(1L, false);
    }

    @Test
    void toggleVideoFavoriteCallsRepoAndReturnsSummary() {
        var v = video(10L, 1L, "a.mp4", "a.mp4");
        when(videoRepo.findById(10L)).thenReturn(Optional.of(v));
        when(videoTagRepo.findTagSlugsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findFirstSeqByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findCountsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());

        var out = service.toggleVideoFavorite(10L, true);

        verify(videoRepo).toggleFavorite(10L, true);
        assertEquals(10L, out.getId());
    }

    @Test
    void toggleVideoBookmarkCallsRepo() {
        var v = video(10L, 1L, "a.mp4", "a.mp4");
        when(videoRepo.findById(10L)).thenReturn(Optional.of(v));
        when(videoTagRepo.findTagSlugsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findFirstSeqByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findCountsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());

        service.toggleVideoBookmark(10L, false);

        verify(videoRepo).toggleBookmark(10L, false);
    }

    @Test
    void recordVideoWatchCallsRepoAndReturnsSummary() {
        var v = video(10L, 1L, "a.mp4", "a.mp4");
        when(videoRepo.findById(10L)).thenReturn(Optional.of(v));
        when(videoTagRepo.findTagSlugsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findFirstSeqByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());
        when(screenshotRepo.findCountsByVideoIds(List.of(10L))).thenReturn(java.util.Map.of());

        service.recordVideoWatch(10L);

        verify(videoRepo).recordWatch(10L);
    }

    @Test
    void videoMutationsThrowWhenVideoMissing() {
        when(videoRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.toggleVideoFavorite(99L, true));
        assertThrows(IllegalArgumentException.class, () -> service.toggleVideoBookmark(99L, true));
        assertThrows(IllegalArgumentException.class, () -> service.recordVideoWatch(99L));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static AvVideo video(long id, long actressId, String relPath, String filename) {
        return AvVideo.builder()
                .id(id).avActressId(actressId).volumeId("qnap_av")
                .relativePath(relPath).filename(filename)
                .extension("mp4").build();
    }

    private static AvActress actress(long id, String stageName, String folderName) {
        return AvActress.builder()
                .id(id).stageName(stageName).folderName(folderName)
                .volumeId("qnap_av").firstSeenAt(java.time.LocalDateTime.now())
                .build();
    }

    private static void initConfigWithVolume(String volumeId, String smbPath) {
        var vol = new VolumeConfig(volumeId, smbPath, "avstars", "qnap2", null);
        var config = new OrganizerConfig(
                null, null, null, null, null, null, null, null,
                List.of(), List.of(vol), List.of(), List.of(), null);
        AppConfig.initializeForTest(config);
    }
}
