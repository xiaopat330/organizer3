package com.organizer3.web;

import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoStreamServiceTest {

    @Mock TitleRepository titleRepo;
    @Mock VideoRepository videoRepo;
    @Mock SmbConnectionFactory smbFactory;

    VideoStreamService service;

    @BeforeEach
    void setUp() {
        service = new VideoStreamService(titleRepo, videoRepo, smbFactory);
    }

    @Test
    void findVideosReturnsExistingFromDb() throws IOException {
        Title title = title("ABP-123");
        Video video = video(1L, title.getId(), "a", "ABP-123.mp4", "/stars/ABP-123/video/ABP-123.mp4");

        when(titleRepo.findByCode("ABP-123")).thenReturn(Optional.of(title));
        when(videoRepo.findByTitle(title.getId())).thenReturn(List.of(video));

        List<VideoStreamService.VideoInfo> result = service.findVideos("ABP-123");

        assertEquals(1, result.size());
        assertEquals("ABP-123.mp4", result.get(0).filename());
        assertEquals(1L, result.get(0).id());
        // Should NOT call smbFactory since videos already exist
        verifyNoInteractions(smbFactory);
    }

    @Test
    void findVideosReturnsEmptyForUnknownTitle() throws IOException {
        when(titleRepo.findByCode("NOPE-999")).thenReturn(Optional.empty());

        List<VideoStreamService.VideoInfo> result = service.findVideos("NOPE-999");

        assertTrue(result.isEmpty());
    }

    @Test
    void findVideosDiscoversFromSmbWhenNoDbRecords() throws IOException {
        Title title = Title.builder()
                .id(10L).code("ABP-123").baseCode("ABP-00123").label("ABP")
                .locations(List.of(TitleLocation.builder()
                        .titleId(10L).volumeId("a").partitionId("stars")
                        .path(Path.of("/stars/ABP-123"))
                        .lastSeenAt(LocalDate.now())
                        .build()))
                .build();

        when(titleRepo.findByCode("ABP-123")).thenReturn(Optional.of(title));
        when(videoRepo.findByTitle(10L)).thenReturn(List.of()); // no existing videos

        // Mock SMB discovery
        SmbShareHandle handle = mock(SmbShareHandle.class);
        when(smbFactory.open("a")).thenReturn(handle);

        // video/ subfolder exists and contains one file
        when(handle.folderExists("/stars/ABP-123/video")).thenReturn(true);
        when(handle.listDirectory("/stars/ABP-123/video")).thenReturn(List.of("ABP-123.mp4"));
        when(handle.fileSize("/stars/ABP-123/video/ABP-123.mp4")).thenReturn(5_000_000_000L);

        // No other subfolders
        when(handle.folderExists(argThat(s -> s != null && !s.equals("/stars/ABP-123/video")
                && !s.equals("/stars/ABP-123")))).thenReturn(false);

        // Root folder listing (no video files at root after subfolder scan)
        when(handle.listDirectory("/stars/ABP-123")).thenReturn(List.of("cover.jpg", "video"));

        // videoRepo.save returns the video with an ID
        when(videoRepo.save(any(Video.class))).thenAnswer(inv -> {
            Video v = inv.getArgument(0);
            return Video.builder()
                    .id(100L).titleId(v.getTitleId()).volumeId(v.getVolumeId())
                    .filename(v.getFilename()).path(v.getPath()).lastSeenAt(v.getLastSeenAt())
                    .build();
        });

        List<VideoStreamService.VideoInfo> result = service.findVideos("ABP-123");

        assertEquals(1, result.size());
        assertEquals("ABP-123.mp4", result.get(0).filename());
        assertEquals(100L, result.get(0).id());
        assertEquals(5_000_000_000L, result.get(0).fileSize());

        // Verify video was persisted
        ArgumentCaptor<Video> captor = ArgumentCaptor.forClass(Video.class);
        verify(videoRepo).save(captor.capture());
        Video saved = captor.getValue();
        assertEquals(10L, saved.getTitleId());
        assertEquals("a", saved.getVolumeId());
        assertEquals("ABP-123.mp4", saved.getFilename());
    }

    @Test
    void findVideosHandlesSmbFailureGracefully() throws IOException {
        Title title = Title.builder()
                .id(10L).code("ABP-123").baseCode("ABP-00123").label("ABP")
                .locations(List.of(TitleLocation.builder()
                        .titleId(10L).volumeId("a").partitionId("stars")
                        .path(Path.of("/stars/ABP-123"))
                        .lastSeenAt(LocalDate.now())
                        .build()))
                .build();

        when(titleRepo.findByCode("ABP-123")).thenReturn(Optional.of(title));
        when(videoRepo.findByTitle(10L)).thenReturn(List.of());
        when(smbFactory.open("a")).thenThrow(new IOException("NAS offline"));

        // Should not throw — returns empty
        List<VideoStreamService.VideoInfo> result = service.findVideos("ABP-123");
        assertTrue(result.isEmpty());
    }

    @Test
    void mimeTypeForCommonExtensions() {
        assertEquals("video/mp4", service.mimeType(video(1L, 1L, "a", "test.mp4", "/test.mp4")));
        assertEquals("video/x-matroska", service.mimeType(video(1L, 1L, "a", "test.mkv", "/test.mkv")));
        assertEquals("video/x-msvideo", service.mimeType(video(1L, 1L, "a", "test.avi", "/test.avi")));
        assertEquals("video/x-ms-wmv", service.mimeType(video(1L, 1L, "a", "test.wmv", "/test.wmv")));
    }

    @Test
    void findVideoByIdDelegatesToRepo() {
        Video video = video(42L, 1L, "a", "test.mp4", "/test.mp4");
        when(videoRepo.findById(42L)).thenReturn(Optional.of(video));

        Optional<Video> result = service.findVideoById(42L);
        assertTrue(result.isPresent());
        assertEquals(42L, result.get().getId());
    }

    // --- helpers ---

    private static Title title(String code) {
        return Title.builder()
                .id(1L).code(code).baseCode(code).label("ABP")
                .locations(List.of(TitleLocation.builder()
                        .titleId(1L).volumeId("a").partitionId("stars")
                        .path(Path.of("/stars/" + code))
                        .lastSeenAt(LocalDate.now())
                        .build()))
                .build();
    }

    private static Video video(long id, long titleId, String volumeId, String filename, String path) {
        return Video.builder()
                .id(id).titleId(titleId).volumeId(volumeId)
                .filename(filename).path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }
}
