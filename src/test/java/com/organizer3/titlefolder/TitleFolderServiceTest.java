package com.organizer3.titlefolder;

import com.organizer3.config.volume.MediaConfig;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    // ── listContents ──────────────────────────────────────────────────────

    /** MediaConfig with only mp4/mkv as video and jpg/png as cover — keeps tests deterministic. */
    private static final MediaConfig TEST_MEDIA = new MediaConfig(List.of("mp4", "mkv"), List.of("jpg", "png"));

    private TitleFolderService serviceWithMedia() {
        return new TitleFolderService(titleRepo, videoRepo, jdbi, TEST_MEDIA);
    }

    @Test
    void listContents_missingFolder_returnsEmpty() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/stars/MIDE-500");
        when(fs.exists(folder)).thenReturn(false);
        when(fs.isDirectory(folder)).thenReturn(false);

        var result = serviceWithMedia().listContents(fs, "MIDE-500", "a", folder);

        assertTrue(result.videos().isEmpty());
        assertTrue(result.covers().isEmpty());
        assertTrue(result.otherFiles().isEmpty());
        assertEquals("a", result.volumeId());
    }

    @Test
    void listContents_emptyFolder_returnsEmpty() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/stars/MIDE-501");
        when(fs.exists(folder)).thenReturn(true);
        when(fs.isDirectory(folder)).thenReturn(true);
        when(fs.listDirectory(folder)).thenReturn(List.of());

        var result = serviceWithMedia().listContents(fs, "MIDE-501", "a", folder);

        assertTrue(result.videos().isEmpty());
        assertTrue(result.covers().isEmpty());
        assertTrue(result.otherFiles().isEmpty());
    }

    @Test
    void listContents_baseLevelCoverOnly() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/stars/MIDE-502");
        Path cover  = folder.resolve("MIDE-502.jpg");
        when(fs.exists(folder)).thenReturn(true);
        when(fs.isDirectory(folder)).thenReturn(true);
        when(fs.listDirectory(folder)).thenReturn(List.of(cover));
        when(fs.isDirectory(cover)).thenReturn(false);
        when(fs.size(cover)).thenReturn(280_000L);

        var result = serviceWithMedia().listContents(fs, "MIDE-502", "a", folder);

        assertTrue(result.videos().isEmpty());
        assertEquals(1, result.covers().size());
        assertEquals("MIDE-502.jpg", result.covers().get(0).filename());
        assertEquals("MIDE-502.jpg", result.covers().get(0).relativePath());
        assertEquals(280_000L, result.covers().get(0).sizeBytes());
        assertTrue(result.otherFiles().isEmpty());
    }

    @Test
    void listContents_videoInSubfolderAndCoverAtBase_canonicalLayout() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder  = Path.of("/stars/MIDE-503");
        Path subDir  = folder.resolve("video");
        Path vidFile = subDir.resolve("MIDE-503.mp4");
        Path cover   = folder.resolve("MIDE-503.jpg");

        when(fs.exists(folder)).thenReturn(true);
        when(fs.isDirectory(folder)).thenReturn(true);
        when(fs.listDirectory(folder)).thenReturn(List.of(cover, subDir));
        when(fs.isDirectory(cover)).thenReturn(false);
        when(fs.isDirectory(subDir)).thenReturn(true);
        when(fs.listDirectory(subDir)).thenReturn(List.of(vidFile));
        when(fs.isDirectory(vidFile)).thenReturn(false);
        when(fs.size(vidFile)).thenReturn(3_000_000_000L);
        when(fs.size(cover)).thenReturn(280_000L);

        var result = serviceWithMedia().listContents(fs, "MIDE-503", "a", folder);

        assertEquals(1, result.videos().size());
        assertEquals("MIDE-503.mp4", result.videos().get(0).filename());
        assertEquals("video/MIDE-503.mp4", result.videos().get(0).relativePath());
        assertEquals(1, result.covers().size());
        assertEquals("MIDE-503.jpg", result.covers().get(0).filename());
        assertTrue(result.otherFiles().isEmpty());
    }

    @Test
    void listContents_videoAtBaseAndInSubfolder_layoutDrift() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder      = Path.of("/stars/MIDE-504");
        Path baseVideo   = folder.resolve("MIDE-504.mkv");
        Path subDir      = folder.resolve("extras");
        Path subVideo    = subDir.resolve("MIDE-504_extra.mp4");

        when(fs.exists(folder)).thenReturn(true);
        when(fs.isDirectory(folder)).thenReturn(true);
        when(fs.listDirectory(folder)).thenReturn(List.of(baseVideo, subDir));
        when(fs.isDirectory(baseVideo)).thenReturn(false);
        when(fs.isDirectory(subDir)).thenReturn(true);
        when(fs.listDirectory(subDir)).thenReturn(List.of(subVideo));
        when(fs.isDirectory(subVideo)).thenReturn(false);
        when(fs.size(baseVideo)).thenReturn(1_000L);
        when(fs.size(subVideo)).thenReturn(2_000L);

        var result = serviceWithMedia().listContents(fs, "MIDE-504", "a", folder);

        assertEquals(2, result.videos().size());
        // base-level video
        assertTrue(result.videos().stream().anyMatch(v -> "MIDE-504.mkv".equals(v.filename())
                && "MIDE-504.mkv".equals(v.relativePath())));
        // subfolder video
        assertTrue(result.videos().stream().anyMatch(v -> "MIDE-504_extra.mp4".equals(v.filename())
                && "extras/MIDE-504_extra.mp4".equals(v.relativePath())));
    }

    @Test
    void listContents_unrecognizedFile_goesToOtherFiles() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder  = Path.of("/stars/MIDE-505");
        Path notes   = folder.resolve("notes.txt");

        when(fs.exists(folder)).thenReturn(true);
        when(fs.isDirectory(folder)).thenReturn(true);
        when(fs.listDirectory(folder)).thenReturn(List.of(notes));
        when(fs.isDirectory(notes)).thenReturn(false);

        var result = serviceWithMedia().listContents(fs, "MIDE-505", "a", folder);

        assertTrue(result.videos().isEmpty());
        assertTrue(result.covers().isEmpty());
        assertEquals(List.of("notes.txt"), result.otherFiles());
    }

    @Test
    void listContents_dbMetadataJoin_videoWithRowGetsMetadata_videoWithoutRowIsNull() throws Exception {
        // Persist a title and ONE video row; list two files on disk.
        long tid = titleRepo.save(title("MIDE-506")).getId();
        Video saved = videoRepo.save(Video.builder()
                .titleId(tid).volumeId("a").filename("MIDE-506.mp4")
                .path(Path.of("/stars/MIDE-506/video/MIDE-506.mp4"))
                .lastSeenAt(LocalDate.of(2026, 4, 1))
                .sizeBytes(3_000_000_000L).durationSec(7200L)
                .width(1920).height(1080).videoCodec("hevc").audioCodec("aac").container("mp4")
                .build());

        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder     = Path.of("/stars/MIDE-506");
        Path subDir     = folder.resolve("video");
        Path vidWithRow = subDir.resolve("MIDE-506.mp4");
        Path vidNoRow   = subDir.resolve("MIDE-506_alt.mp4");

        when(fs.exists(folder)).thenReturn(true);
        when(fs.isDirectory(folder)).thenReturn(true);
        when(fs.listDirectory(folder)).thenReturn(List.of(subDir));
        when(fs.isDirectory(subDir)).thenReturn(true);
        when(fs.listDirectory(subDir)).thenReturn(List.of(vidWithRow, vidNoRow));
        when(fs.isDirectory(vidWithRow)).thenReturn(false);
        when(fs.isDirectory(vidNoRow)).thenReturn(false);
        when(fs.size(vidWithRow)).thenReturn(3_000_000_000L);
        when(fs.size(vidNoRow)).thenReturn(500_000_000L);

        var result = serviceWithMedia().listContents(fs, "MIDE-506", "a", folder);

        assertEquals(2, result.videos().size());
        TitleFolderService.FolderVideo withRow = result.videos().stream()
                .filter(v -> "MIDE-506.mp4".equals(v.filename())).findFirst().orElseThrow();
        assertEquals(saved.getId(), withRow.videoId());
        assertEquals(7200L, withRow.durationSec());
        assertEquals(1920, withRow.width());
        assertEquals("hevc", withRow.videoCodec());

        TitleFolderService.FolderVideo noRow = result.videos().stream()
                .filter(v -> "MIDE-506_alt.mp4".equals(v.filename())).findFirst().orElseThrow();
        assertNull(noRow.videoId());
        assertNull(noRow.durationSec());
        assertNull(noRow.width());
    }

    @Test
    void listContents_sizeIoException_treatedAsNull() throws Exception {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/stars/MIDE-507");
        Path cover  = folder.resolve("MIDE-507.jpg");

        when(fs.exists(folder)).thenReturn(true);
        when(fs.isDirectory(folder)).thenReturn(true);
        when(fs.listDirectory(folder)).thenReturn(List.of(cover));
        when(fs.isDirectory(cover)).thenReturn(false);
        when(fs.size(cover)).thenThrow(new IOException("NAS hiccup"));

        // Should not throw — size silently becomes null.
        var result = serviceWithMedia().listContents(fs, "MIDE-507", "a", folder);

        assertEquals(1, result.covers().size());
        assertNull(result.covers().get(0).sizeBytes());
    }

    // ── planNormalization ──────────────────────────────────────────────────

    /** Service with fixed media config (mp4/mkv + jpg/png) for normalization tests. */
    private TitleFolderService planService() {
        return new TitleFolderService(titleRepo, videoRepo, jdbi, TEST_MEDIA, null);
    }

    /** Build a mock FS with a folder containing base children and one level of subdirectories. */
    private static VolumeFileSystem folderFs(Path folder,
                                             List<Path> baseFiles,
                                             List<Path> baseDirs,
                                             Map<Path, List<Path>> dirContents) throws IOException {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        List<Path> allBase = new ArrayList<>(baseFiles);
        allBase.addAll(baseDirs);
        when(fs.listDirectory(folder)).thenReturn(allBase);
        for (Path f : baseFiles) {
            when(fs.isDirectory(f)).thenReturn(false);
        }
        for (Path d : baseDirs) {
            when(fs.isDirectory(d)).thenReturn(true);
            List<Path> subs = dirContents.getOrDefault(d, List.of());
            when(fs.listDirectory(d)).thenReturn(subs);
            for (Path sub : subs) {
                when(fs.isDirectory(sub)).thenReturn(false);
            }
        }
        return fs;
    }

    @Test
    void planNormalization_singleVideo_alreadyCanonical() throws IOException {
        Path folder = Path.of("/jav/MIDE-001");
        Path subDir = folder.resolve("video");
        Path vid    = subDir.resolve("MIDE-001.mp4");
        Path cover  = folder.resolve("MIDE-001.jpg");

        VolumeFileSystem fs = folderFs(folder, List.of(cover), List.of(subDir),
                Map.of(subDir, List.of(vid)));

        var plan = planService().planNormalization(fs, "MIDE-001", folder, Set.of());

        assertTrue(plan.alreadyNormalized());
        assertEquals(2, plan.entries().size());
        plan.entries().forEach(e -> assertTrue(e.alreadyCanonical(), "expected canonical: " + e));
    }

    @Test
    void planNormalization_singleVideo_needsRename() throws IOException {
        // Video is at base with wrong prefix (removelist would strip watermark in real use,
        // but here we just use a filename that differs from the canonical form).
        // "old_rip_MIDE-002.mp4" — regex fallback gives freeform="", canonical = "MIDE-002.mp4".
        // But video is at BASE, so target = "video/MIDE-002.mp4" (needs move into subfolder).
        Path folder = Path.of("/jav/MIDE-002");
        Path vid    = folder.resolve("old_rip_MIDE-002.mp4");  // doesn't start with code
        Path cover  = folder.resolve("MIDE-002.jpg");

        VolumeFileSystem fs = folderFs(folder, List.of(cover, vid), List.of(), Map.of());

        var plan = planService().planNormalization(fs, "MIDE-002", folder, Set.of());

        assertFalse(plan.alreadyNormalized());
        var videoEntry = plan.entries().stream()
                .filter(e -> "video".equals(e.kind())).findFirst().orElseThrow();
        assertFalse(videoEntry.alreadyCanonical());
        assertNotNull(videoEntry.to());
        // Target is in video/ subfolder with canonical name
        assertTrue(videoEntry.to().startsWith("video/"), "expected video/ prefix: " + videoEntry.to());
        assertTrue(videoEntry.to().endsWith("MIDE-002.mp4"), "expected canonical name in: " + videoEntry.to());
    }

    @Test
    void planNormalization_singleVideo_needsBothRenameAndMove() throws IOException {
        // Video is at base with wrong name — needs move to video/ and rename.
        Path folder = Path.of("/jav/MIDE-003");
        Path vid    = folder.resolve("mide-003_download.mp4");

        VolumeFileSystem fs = folderFs(folder, List.of(vid), List.of(), Map.of());

        var plan = planService().planNormalization(fs, "MIDE-003", folder, Set.of());

        assertFalse(plan.alreadyNormalized());
        var videoEntry = plan.entries().stream()
                .filter(e -> "video".equals(e.kind())).findFirst().orElseThrow();
        // target should be in video/ subfolder
        assertTrue(videoEntry.to().startsWith("video/"), "expected video/ prefix: " + videoEntry.to());
    }

    @Test
    void planNormalization_coverNeedsRename() throws IOException {
        // Cover named "cover.jpg" → should become "MIDE-004.jpg"
        Path folder = Path.of("/jav/MIDE-004");
        Path cover  = folder.resolve("cover.jpg");
        Path subDir = folder.resolve("video");
        Path vid    = subDir.resolve("MIDE-004.mp4");

        VolumeFileSystem fs = folderFs(folder, List.of(cover), List.of(subDir),
                Map.of(subDir, List.of(vid)));

        var plan = planService().planNormalization(fs, "MIDE-004", folder, Set.of());

        assertFalse(plan.alreadyNormalized());
        var coverEntry = plan.entries().stream()
                .filter(e -> "cover".equals(e.kind())).findFirst().orElseThrow();
        assertFalse(coverEntry.alreadyCanonical());
        assertEquals("MIDE-004.jpg", coverEntry.to());
    }

    @Test
    void planNormalization_multiVideo_conflictingCanonicalName() throws IOException {
        // Two videos that both resolve to the same canonical name → conflict (to=null).
        Path folder = Path.of("/jav/MIDE-005");
        Path vid1   = folder.resolve("MIDE-005-a.mp4");
        Path vid2   = folder.resolve("MIDE-005-b.mp4");

        // Force both to resolve to the same canonical name by picking names where
        // freeformSuffix is empty for both (both look like code only).
        // We'll use different files that both strip to MIDE-005.mp4.
        Path vid1b  = folder.resolve("MIDE-005.mp4");
        Path vid2b  = folder.resolve("mide-005.mp4");  // same target, different case

        VolumeFileSystem fs = folderFs(folder, List.of(vid1b, vid2b), List.of(), Map.of());

        var plan = planService().planNormalization(fs, "MIDE-005", folder, Set.of());

        // Both entries should be conflict=true because they target the same path.
        long conflicts = plan.entries().stream().filter(TitleFolderService.NormalizationPlanEntry::conflict).count();
        assertEquals(2, conflicts);
        assertFalse(plan.alreadyNormalized());
    }

    @Test
    void planNormalization_excludeRelPathsSkipsFile() throws IOException {
        Path folder = Path.of("/jav/MIDE-006");
        Path vid    = folder.resolve("video/MIDE-006.mp4");
        Path cover  = folder.resolve("MIDE-006.jpg");
        Path subDir = folder.resolve("video");

        VolumeFileSystem fs = folderFs(folder, List.of(cover), List.of(subDir),
                Map.of(subDir, List.of(vid)));

        // Exclude the video (staged for trash).
        var plan = planService().planNormalization(fs, "MIDE-006", folder, Set.of("video/MIDE-006.mp4"));

        // Only the cover should be in the plan.
        assertEquals(1, plan.entries().size());
        assertEquals("cover", plan.entries().get(0).kind());
    }

    @Test
    void planNormalization_h265VideoGoesToH265Subfolder() throws IOException {
        Path folder = Path.of("/jav/MIDE-007");
        Path vid    = folder.resolve("MIDE-007-h265.mkv");

        VolumeFileSystem fs = folderFs(folder, List.of(vid), List.of(), Map.of());

        var plan = planService().planNormalization(fs, "MIDE-007", folder, Set.of());

        var videoEntry = plan.entries().stream()
                .filter(e -> "video".equals(e.kind())).findFirst().orElseThrow();
        assertTrue(videoEntry.to().startsWith("h265/"), "expected h265/ prefix: " + videoEntry.to());
    }

    @Test
    void planNormalization_4kVideoGoesTo4KSubfolder() throws IOException {
        Path folder = Path.of("/jav/MIDE-008");
        Path vid    = folder.resolve("MIDE-008-4k.mkv");

        VolumeFileSystem fs = folderFs(folder, List.of(vid), List.of(), Map.of());

        var plan = planService().planNormalization(fs, "MIDE-008", folder, Set.of());

        var videoEntry = plan.entries().stream()
                .filter(e -> "video".equals(e.kind())).findFirst().orElseThrow();
        assertTrue(videoEntry.to().startsWith("4K/"), "expected 4K/ prefix: " + videoEntry.to());
    }

    // ── executeNormalization ───────────────────────────────────────────────

    @Test
    void executeNormalization_emptyMoves_returnsZero() throws IOException {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/jav/MIDE-100");

        var outcome = planService().executeNormalization(fs, folder, List.of());

        assertEquals(0, outcome.movedCount());
        assertTrue(outcome.moved().isEmpty());
        verifyNoInteractions(fs);
    }

    @Test
    void executeNormalization_singleRename_movesFile() throws IOException {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/jav/MIDE-101");
        Path absFrom = folder.resolve("video/old_name.mp4").normalize();
        Path absTo   = folder.resolve("video/MIDE-101.mp4").normalize();
        when(fs.exists(absFrom)).thenReturn(true);
        when(fs.exists(absTo)).thenReturn(false);

        var outcome = planService().executeNormalization(fs, folder,
                List.of(new TitleFolderService.MovePair("video/old_name.mp4", "video/MIDE-101.mp4")));

        assertEquals(1, outcome.movedCount());
        verify(fs).move(absFrom, absTo);
    }

    @Test
    void executeNormalization_noOpEntry_skipped() throws IOException {
        // from == to → no move should be attempted
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder  = Path.of("/jav/MIDE-102");
        Path absPath = folder.resolve("video/MIDE-102.mp4").normalize();
        when(fs.exists(absPath)).thenReturn(true);

        var outcome = planService().executeNormalization(fs, folder,
                List.of(new TitleFolderService.MovePair("video/MIDE-102.mp4", "video/MIDE-102.mp4")));

        assertEquals(0, outcome.movedCount());
        verify(fs, never()).move(any(), any());
    }

    @Test
    void executeNormalization_missingSource_throwsBeforeMutation() throws IOException {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/jav/MIDE-103");
        Path absFrom = folder.resolve("video/missing.mp4").normalize();
        when(fs.exists(absFrom)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> planService().executeNormalization(fs, folder,
                        List.of(new TitleFolderService.MovePair("video/missing.mp4", "video/MIDE-103.mp4"))));

        verify(fs, never()).move(any(), any());
    }

    @Test
    void executeNormalization_duplicateTarget_throwsBeforeMutation() throws IOException {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder  = Path.of("/jav/MIDE-104");
        Path absA    = folder.resolve("video/a.mp4").normalize();
        Path absB    = folder.resolve("video/b.mp4").normalize();
        Path absTo   = folder.resolve("video/MIDE-104.mp4").normalize();
        when(fs.exists(absA)).thenReturn(true);
        when(fs.exists(absB)).thenReturn(true);
        when(fs.exists(absTo)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> planService().executeNormalization(fs, folder, List.of(
                        new TitleFolderService.MovePair("video/a.mp4", "video/MIDE-104.mp4"),
                        new TitleFolderService.MovePair("video/b.mp4", "video/MIDE-104.mp4")
                )));

        verify(fs, never()).move(any(), any());
    }

    @Test
    void executeNormalization_targetCollidesWithUntouchedFile_throwsBeforeMutation() throws IOException {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder  = Path.of("/jav/MIDE-105");
        Path absFrom = folder.resolve("video/old.mp4").normalize();
        Path absTo   = folder.resolve("video/MIDE-105.mp4").normalize();
        when(fs.exists(absFrom)).thenReturn(true);
        when(fs.exists(absTo)).thenReturn(true);   // exists and NOT in the from-set

        assertThrows(IllegalArgumentException.class,
                () -> planService().executeNormalization(fs, folder,
                        List.of(new TitleFolderService.MovePair("video/old.mp4", "video/MIDE-105.mp4"))));

        verify(fs, never()).move(any(), any());
    }

    @Test
    void executeNormalization_pathTraversal_throwsBeforeMutation() {
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        Path folder = Path.of("/jav/MIDE-106");

        assertThrows(IllegalArgumentException.class,
                () -> planService().executeNormalization(fs, folder,
                        List.of(new TitleFolderService.MovePair("../../etc/passwd", "video/out.mp4"))));
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
