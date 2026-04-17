package com.organizer3.organize;

import com.organizer3.config.volume.MediaConfig;
import com.organizer3.filesystem.LocalFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TitleNormalizerServiceTest {

    @TempDir Path tempDir;

    private LocalFileSystem fs;
    private TitleNormalizerService svc;

    @BeforeEach
    void setUp() {
        fs = new LocalFileSystem();
        svc = new TitleNormalizerService(MediaConfig.DEFAULTS);
    }

    // ── cover rename ────────────────────────────────────────────────────────

    @Test
    void singleCover_renamesToCanonical() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(t.resolve("MIDE-123.jpg")));
        assertFalse(Files.exists(t.resolve("mide123pl.jpg")));
        assertEquals(1, r.applied().size());
        assertEquals("cover-rename", r.applied().get(0).op());
    }

    @Test
    void coverAlreadyCanonical_noOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s -> s.reason().contains("already canonical")));
    }

    @Test
    void multipleCovers_skipped() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));
        Files.createFile(t.resolve("mide123pl (2).jpg"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s ->
                "cover".equals(s.kind()) && s.reason().contains("multiple covers")));
    }

    @Test
    void noCover_reported() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.skipped().stream().anyMatch(s ->
                "cover".equals(s.kind()) && s.reason().contains("no cover at base")));
    }

    // ── video rename ────────────────────────────────────────────────────────

    @Test
    void singleVideoAtBase_renamesToCanonical() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));
        Files.createFile(t.resolve("mide-123-random-suffix.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(t.resolve("MIDE-123.mp4")));
        assertFalse(Files.exists(t.resolve("mide-123-random-suffix.mp4")));
        assertEquals(2, r.applied().size(), "cover + video both renamed");
    }

    @Test
    void singleVideoInSubfolder_renamesInPlace() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("random-upload-name.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(videoDir.resolve("MIDE-123.mp4")));
        assertEquals(1, r.applied().size());
        assertEquals("video-rename", r.applied().get(0).op());
    }

    @Test
    void multipleVideos_skipped() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("disc1.mp4"));
        Files.createFile(videoDir.resolve("disc2.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.skipped().stream().anyMatch(s ->
                "video".equals(s.kind()) && s.reason().contains("multi-file title")));
    }

    @Test
    void videoAlreadyCanonical_noOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("MIDE-123.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.applied().isEmpty());
        assertTrue(r.skipped().stream().anyMatch(s ->
                "video".equals(s.kind()) && s.reason().contains("already canonical")));
    }

    @Test
    void videoAtBaseAndInSubfolder_countedTogetherAsMulti() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("foo.mp4"));
        Path videoDir = Files.createDirectory(t.resolve("video"));
        Files.createFile(videoDir.resolve("bar.mp4"));

        var r = svc.apply(fs, t, "MIDE-123", false);

        assertTrue(r.skipped().stream().anyMatch(s -> s.reason().contains("multi-file")));
    }

    // ── dry-run ─────────────────────────────────────────────────────────────

    @Test
    void dryRun_producesPlanWithoutRenaming() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123pl.jpg"));

        var r = svc.apply(fs, t, "MIDE-123", true);

        assertTrue(r.dryRun());
        assertEquals(1, r.planned().size());
        assertTrue(r.applied().isEmpty());
        assertTrue(Files.exists(t.resolve("mide123pl.jpg")), "dry-run must leave file untouched");
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    void rejectsMissingFolder() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.apply(fs, tempDir.resolve("nope"), "X-1", true));
    }

    @Test
    void rejectsBlankCode() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.apply(fs, t, " ", true));
    }

    @Test
    void preservesExtensionCase() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("title"));
        Files.createFile(t.resolve("mide123.JPEG"));

        svc.apply(fs, t, "MIDE-123", false);

        assertTrue(Files.exists(t.resolve("MIDE-123.JPEG")));
    }
}
