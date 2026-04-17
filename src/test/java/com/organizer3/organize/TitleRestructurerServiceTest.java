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

class TitleRestructurerServiceTest {

    @TempDir Path tempDir;

    private LocalFileSystem fs;
    private TitleRestructurerService svc;

    @BeforeEach
    void setUp() {
        fs = new LocalFileSystem();
        svc = new TitleRestructurerService(MediaConfig.DEFAULTS);
    }

    @Test
    void movesPlainVideoToVideoSubfolder() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Files.createFile(t.resolve("cover.jpg"));
        Files.createFile(t.resolve("MIDE-123.mp4"));

        svc.apply(fs, t, false);

        assertTrue(Files.exists(t.resolve("video/MIDE-123.mp4")));
        assertFalse(Files.exists(t.resolve("MIDE-123.mp4")));
        assertTrue(Files.exists(t.resolve("cover.jpg")));
    }

    @Test
    void movesH265VideoToH265Subfolder() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Files.createFile(t.resolve("MIDE-123-h265.mp4"));

        svc.apply(fs, t, false);

        assertTrue(Files.exists(t.resolve("h265/MIDE-123-h265.mp4")));
    }

    @Test
    void moves4KVideoTo4KSubfolder() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Files.createFile(t.resolve("MIDE-123-4K.mp4"));

        svc.apply(fs, t, false);

        assertTrue(Files.exists(t.resolve("4K/MIDE-123-4K.mp4")));
    }

    @Test
    void caseInsensitiveHintMatching() {
        assertEquals("4K",    TitleRestructurerService.pickSubfolder("X-4K.mp4"));
        assertEquals("4K",    TitleRestructurerService.pickSubfolder("X-4k.mp4"));
        assertEquals("h265",  TitleRestructurerService.pickSubfolder("X-H265.mp4"));
        assertEquals("video", TitleRestructurerService.pickSubfolder("X.mp4"));
    }

    @Test
    void videosAlreadyInSubfolderAreLeftAlone() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Path vdir = Files.createDirectory(t.resolve("video"));
        Files.createFile(vdir.resolve("already.mp4"));

        var r = svc.apply(fs, t, false);

        assertTrue(r.planned().isEmpty());
        assertTrue(Files.exists(vdir.resolve("already.mp4")));
    }

    @Test
    void coversLeftAtBase() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Files.createFile(t.resolve("MIDE-123.jpg"));
        Files.createFile(t.resolve("MIDE-123.mp4"));

        svc.apply(fs, t, false);

        assertTrue(Files.exists(t.resolve("MIDE-123.jpg")));
    }

    @Test
    void collisionInTargetSubfolderSkipped() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Path vdir = Files.createDirectory(t.resolve("video"));
        Files.createFile(vdir.resolve("MIDE-123.mp4"));   // already there
        Files.createFile(t.resolve("MIDE-123.mp4"));      // and at base (same name)

        var r = svc.apply(fs, t, false);

        assertEquals(1, r.collisions().size());
        assertTrue(Files.exists(t.resolve("MIDE-123.mp4")), "base file not moved on collision");
        assertTrue(Files.exists(vdir.resolve("MIDE-123.mp4")));
    }

    @Test
    void multipleVideosAtBaseEachRoutedByHint() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Files.createFile(t.resolve("a.mp4"));
        Files.createFile(t.resolve("b-h265.mp4"));
        Files.createFile(t.resolve("c-4K.mp4"));

        svc.apply(fs, t, false);

        assertTrue(Files.exists(t.resolve("video/a.mp4")));
        assertTrue(Files.exists(t.resolve("h265/b-h265.mp4")));
        assertTrue(Files.exists(t.resolve("4K/c-4K.mp4")));
    }

    @Test
    void dryRunProducesPlanWithoutMoving() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Files.createFile(t.resolve("MIDE-123.mp4"));

        var r = svc.apply(fs, t, true);

        assertTrue(r.dryRun());
        assertEquals(1, r.planned().size());
        assertTrue(r.moved().isEmpty());
        assertTrue(Files.exists(t.resolve("MIDE-123.mp4")), "dry-run must leave file in place");
    }

    @Test
    void noVideosAtBaseIsNoOp() throws IOException {
        Path t = Files.createDirectory(tempDir.resolve("t"));
        Files.createFile(t.resolve("cover.jpg"));

        var r = svc.apply(fs, t, false);

        assertTrue(r.planned().isEmpty());
        assertTrue(r.moved().isEmpty());
    }

    @Test
    void rejectsMissingFolder() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.apply(fs, tempDir.resolve("nope"), true));
    }
}
