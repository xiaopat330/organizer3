package com.organizer3.titlefolder;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.trash.Trash;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TitleFolderServiceTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiVideoRepository videoRepo;
    private TitleFolderService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('b', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        videoRepo = new JdbiVideoRepository(jdbi);
        service = new TitleFolderService(titleRepo, videoRepo, jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── analyzeVideos verdict matrix ────────────────────────────────────────

    @Test
    void analyzeVideos_unknownTitle_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.analyzeVideos("MIDE-999"));
        assertTrue(ex.getMessage().contains("MIDE-999"));
    }

    @Test
    void analyzeVideos_noVideos_returnsNoVideos() {
        long tid = titleRepo.save(title("MIDE-001")).getId();
        var r = service.analyzeVideos("MIDE-001");
        assertEquals("no_videos", r.verdict());
        assertEquals(0, r.videoCount());
        assertTrue(r.videos().isEmpty());
    }

    @Test
    void analyzeVideos_singleVideo_returnsSingleVideo() {
        long tid = titleRepo.save(title("MIDE-002")).getId();
        videoRepo.save(video(tid, "a.mkv", "/jav/MIDE-002/a.mkv", 1000L));
        assertEquals("single_video", service.analyzeVideos("MIDE-002").verdict());
    }

    @Test
    void analyzeVideos_missingDuration_returnsInsufficient() {
        long tid = titleRepo.save(title("MIDE-003")).getId();
        videoRepo.save(video(tid, "a.mkv", "/jav/MIDE-003/a.mkv", 3600L));
        videoRepo.save(video(tid, "b.mkv", "/jav/MIDE-003/b.mkv", null));
        assertEquals("insufficient_metadata", service.analyzeVideos("MIDE-003").verdict());
    }

    @Test
    void analyzeVideos_tightDurations_returnsLikelyDuplicates() {
        long tid = titleRepo.save(title("MIDE-004")).getId();
        videoRepo.save(video(tid, "a.mkv", "/jav/MIDE-004/a.mkv", 3600L));
        videoRepo.save(video(tid, "b.mkv", "/jav/MIDE-004/b.mkv", 3610L));   // within 30s
        assertEquals("likely_duplicates", service.analyzeVideos("MIDE-004").verdict());
    }

    @Test
    void analyzeVideos_wideDurations_returnsLikelySet() {
        long tid = titleRepo.save(title("MIDE-005")).getId();
        videoRepo.save(video(tid, "a.mkv", "/jav/MIDE-005/a.mkv", 3600L));
        videoRepo.save(video(tid, "b.mkv", "/jav/MIDE-005/b.mkv", 7200L));   // 1h apart
        assertEquals("likely_set", service.analyzeVideos("MIDE-005").verdict());
    }

    @Test
    void analyzeVideos_midGap_returnsAmbiguous() {
        long tid = titleRepo.save(title("MIDE-006")).getId();
        videoRepo.save(video(tid, "a.mkv", "/jav/MIDE-006/a.mkv", 3600L));
        videoRepo.save(video(tid, "b.mkv", "/jav/MIDE-006/b.mkv", 3660L));   // 60s gap — between thresholds
        assertEquals("ambiguous", service.analyzeVideos("MIDE-006").verdict());
    }

    @Test
    void analyzeVideos_metadataPropagatedToRows() {
        long tid = titleRepo.save(title("MIDE-007")).getId();
        Video saved = videoRepo.save(Video.builder()
                .titleId(tid).volumeId("a").filename("a.mkv")
                .path(Path.of("/jav/MIDE-007/a.mkv"))
                .lastSeenAt(LocalDate.of(2026, 4, 1))
                .sizeBytes(2_000_000_000L).durationSec(3600L)
                .width(1920).height(1080)
                .videoCodec("h264").audioCodec("aac").container("mkv")
                .build());
        var r = service.analyzeVideos("MIDE-007");
        assertEquals(1, r.videos().size());
        var row = r.videos().get(0);
        assertEquals(saved.getId(), row.videoId());
        assertEquals("a.mkv", row.filename());
        assertEquals(1920, row.width());
        assertEquals(1080, row.height());
        assertEquals("h264", row.videoCodec());
        assertEquals("aac", row.audioCodec());
        assertEquals("mkv", row.container());
    }

    // ── findTitleFolder ────────────────────────────────────────────────────

    @Test
    void findTitleFolder_returnsLivePath() {
        Title t = titleRepo.save(title("MIDE-100"));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 'a', 'library', '/stars/popular/MIDE-100', '2026-04-01T00:00:00Z')",
                t.getId()));
        Optional<Path> p = service.findTitleFolder("MIDE-100", "a");
        assertTrue(p.isPresent());
        assertEquals("/stars/popular/MIDE-100", p.get().toString());
    }

    @Test
    void findTitleFolder_caseInsensitiveOnCode() {
        Title t = titleRepo.save(title("MIDE-101"));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 'a', 'library', '/p/MIDE-101', '2026-04-01T00:00:00Z')",
                t.getId()));
        assertTrue(service.findTitleFolder("mide-101", "a").isPresent());
    }

    @Test
    void findTitleFolder_skipsStaleRows() {
        Title t = titleRepo.save(title("MIDE-102"));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at, stale_since) VALUES (?, 'a', 'library', '/p/MIDE-102', '2026-04-01T00:00:00Z', '2026-04-15T00:00:00Z')",
                t.getId()));
        assertTrue(service.findTitleFolder("MIDE-102", "a").isEmpty());
    }

    @Test
    void findTitleFolder_wrongVolume_returnsEmpty() {
        Title t = titleRepo.save(title("MIDE-103"));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?, 'a', 'library', '/p/MIDE-103', '2026-04-01T00:00:00Z')",
                t.getId()));
        assertTrue(service.findTitleFolder("MIDE-103", "b").isEmpty());
    }

    // ── isCover / listCovers ───────────────────────────────────────────────

    @Test
    void isCover_recognizesAllExtensions() {
        assertTrue(TitleFolderService.isCover("a.jpg"));
        assertTrue(TitleFolderService.isCover("a.JPEG"));
        assertTrue(TitleFolderService.isCover("a.png"));
        assertTrue(TitleFolderService.isCover("a.WebP"));
    }

    @Test
    void isCover_rejectsOthers() {
        assertFalse(TitleFolderService.isCover("a.mkv"));
        assertFalse(TitleFolderService.isCover("noext"));
        assertFalse(TitleFolderService.isCover("trailing."));
        assertFalse(TitleFolderService.isCover(null));
    }

    @Test
    void listCovers_returnsBaseLevelCoversIgnoringSubdirsAndNonCovers() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/stars/popular/MIDE-200");
        Path subDir = folder.resolve("video");
        Path c1 = folder.resolve("MIDE-200.jpg");
        Path c2 = folder.resolve("cover_alt.png");
        Path notCover = folder.resolve("notes.txt");
        when(fs.listDirectory(folder)).thenReturn(List.of(c1, c2, notCover, subDir));
        when(fs.isDirectory(subDir)).thenReturn(true);
        when(fs.isDirectory(c1)).thenReturn(false);
        when(fs.isDirectory(c2)).thenReturn(false);
        when(fs.isDirectory(notCover)).thenReturn(false);

        List<String> covers = service.listCovers(fs, folder);
        assertEquals(List.of("MIDE-200.jpg", "cover_alt.png"), covers);
    }

    @Test
    void listCovers_wrapsIOExceptionAsIllegalArgument() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/x");
        when(fs.listDirectory(folder)).thenThrow(new IOException("boom"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.listCovers(fs, folder));
        assertTrue(ex.getMessage().contains("Failed to list cover candidates"));
    }

    // ── trashVideo / trashCover ────────────────────────────────────────────

    @Test
    void trashVideo_success_movesAndDeletesDbRow() throws Exception {
        long tid = titleRepo.save(title("MIDE-300")).getId();
        Video v = videoRepo.save(video(tid, "v.mkv", "/jav/MIDE-300/v.mkv", 1L));

        Trash trash = mock(Trash.class);
        Path trashedPath = Path.of("/_trash/jav/MIDE-300/v.mkv");
        when(trash.trashItem(eq(v.getPath()), any()))
                .thenReturn(new Trash.Result(trashedPath, Path.of("/sidecar")));

        var outcome = service.trashVideo(trash, v, "test");

        assertTrue(outcome.success());
        assertEquals(v.getPath(), outcome.source());
        assertEquals(trashedPath, outcome.trashedTo());
        assertNull(outcome.error());
        // DB row gone
        assertTrue(videoRepo.findByTitle(tid).isEmpty());
    }

    @Test
    void trashVideo_ioFailure_keepsDbRowAndReportsError() throws Exception {
        long tid = titleRepo.save(title("MIDE-301")).getId();
        Video v = videoRepo.save(video(tid, "v.mkv", "/jav/MIDE-301/v.mkv", 1L));

        Trash trash = mock(Trash.class);
        when(trash.trashItem(any(), any())).thenThrow(new IOException("net down"));

        var outcome = service.trashVideo(trash, v, "test");

        assertFalse(outcome.success());
        assertEquals("net down", outcome.error());
        // DB row preserved
        assertEquals(1, videoRepo.findByTitle(tid).size());
    }

    @Test
    void trashCover_success_returnsTrashedPath() throws Exception {
        Trash trash = mock(Trash.class);
        Path folder = Path.of("/stars/popular/MIDE-400");
        Path expectedSrc = folder.resolve("cover_alt.png");
        Path trashedPath = Path.of("/_trash/stars/popular/MIDE-400/cover_alt.png");
        when(trash.trashItem(eq(expectedSrc), any()))
                .thenReturn(new Trash.Result(trashedPath, Path.of("/sidecar")));

        var outcome = service.trashCover(trash, folder, "cover_alt.png", "test");

        assertTrue(outcome.success());
        assertEquals(expectedSrc, outcome.source());
        assertEquals(trashedPath, outcome.trashedTo());
    }

    @Test
    void trashCover_ioFailure_capturesErrorWithoutThrowing() throws Exception {
        Trash trash = mock(Trash.class);
        Path folder = Path.of("/stars/popular/MIDE-401");
        when(trash.trashItem(any(), any())).thenThrow(new IOException("disk full"));

        var outcome = service.trashCover(trash, folder, "cover.jpg", "test");

        assertFalse(outcome.success());
        assertEquals("disk full", outcome.error());
        assertEquals(folder.resolve("cover.jpg"), outcome.source());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static Video video(long titleId, String filename, String pathStr, Long durationSec) {
        return Video.builder()
                .titleId(titleId).volumeId("a").filename(filename).path(Path.of(pathStr))
                .lastSeenAt(LocalDate.of(2026, 4, 1))
                .sizeBytes(1_000_000L).durationSec(durationSec).build();
    }
}
