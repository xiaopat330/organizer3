package com.organizer3.sandbox.organize;

import com.organizer3.organize.TitleRestructurerService;
import com.organizer3.sandbox.SandboxTestBase;
import com.organizer3.sandbox.SandboxTitleBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates {@link TitleRestructurerService} over real SMB.
 *
 * <p>SMB-differentiating: atomic move across subfolders on a real SMB share
 * (rename vs copy+delete fallback path).
 */
class RestructureSandboxTest extends SandboxTestBase {

    private static final String CODE = "MIDE-123";

    TitleRestructurerService service;
    Path restructureDir;

    @BeforeEach
    void setUpService() throws Exception {
        service = new TitleRestructurerService(config.media());
        restructureDir = methodRunDir.resolve("restructure");
        fs.createDirectories(restructureDir);
    }

    @Test
    void videoAtBaseMovedToSubfolder() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(restructureDir)
                .withCode(CODE)
                .videoAtBase()
                .build(fs);

        TitleRestructurerService.Result result = service.apply(fs, titleDir, false);

        assertFalse(result.moved().isEmpty(), "Expected video to be moved");
        assertTrue(result.failed().isEmpty(), "No moves should fail");

        List<Path> videoDir = fs.listDirectory(titleDir.resolve("video"));
        assertTrue(videoDir.stream().anyMatch(p -> p.getFileName().toString().equals("mide123.mp4")),
                "Video should be in video/ subfolder, got: " + videoDir);
        assertFalse(fs.exists(titleDir.resolve("mide123.mp4")),
                "Video should no longer be at title root");
    }

    @Test
    void h265VideoRoutedToH265Folder() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(restructureDir)
                .withCode(CODE)
                .withVideo("MIDE-123-h265.mp4")
                .videoAtBase()
                .build(fs);

        TitleRestructurerService.Result result = service.apply(fs, titleDir, false);

        assertFalse(result.moved().isEmpty(), "Expected h265 video to be moved");
        assertTrue(result.failed().isEmpty(), "No moves should fail");

        List<Path> h265Dir = fs.listDirectory(titleDir.resolve("h265"));
        assertTrue(h265Dir.stream().anyMatch(p -> p.getFileName().toString().equals("MIDE-123-h265.mp4")),
                "h265 video should be in h265/ subfolder, got: " + h265Dir);
    }

    @Test
    void videoAlreadyInSubfolder_noOp() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(restructureDir)
                .withCode(CODE)
                .videoInSubfolder("video")
                .build(fs);

        TitleRestructurerService.Result result = service.apply(fs, titleDir, false);

        assertTrue(result.planned().isEmpty(), "No moves should be planned when video already in subfolder");
        assertTrue(result.moved().isEmpty(), "No moves should be executed");
    }

    @Test
    void collisionSkipped() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(restructureDir)
                .withCode(CODE)
                .videoAtBase()
                .build(fs);
        // Pre-create the destination to cause a collision
        Path videoSubDir = titleDir.resolve("video");
        fs.createDirectories(videoSubDir);
        fs.writeFile(videoSubDir.resolve("mide123.mp4"), new byte[]{1});

        TitleRestructurerService.Result result = service.apply(fs, titleDir, false);

        assertFalse(result.collisions().isEmpty(), "Expected collision to be detected");
        assertTrue(result.moved().isEmpty(), "No moves should succeed when destination exists");
    }

    @Test
    void dryRunDoesNotMutate() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(restructureDir)
                .withCode(CODE)
                .videoAtBase()
                .build(fs);

        TitleRestructurerService.Result result = service.apply(fs, titleDir, true);

        assertTrue(result.dryRun(), "Result should indicate dry run");
        assertFalse(result.planned().isEmpty(), "Should report move needed");
        assertTrue(result.moved().isEmpty(), "Dry run must not move any files");

        assertTrue(fs.exists(titleDir.resolve("mide123.mp4")),
                "Video must still be at title root after dry run");
    }
}
