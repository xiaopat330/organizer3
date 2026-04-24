package com.organizer3.sandbox.organize;

import com.organizer3.organize.TitleNormalizerService;
import com.organizer3.sandbox.SandboxTestBase;
import com.organizer3.sandbox.SandboxTitleBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates {@link TitleNormalizerService} over real SMB.
 *
 * <p>SMB-differentiating: confirms that rename succeeds on an actual NAS share where filename
 * casing and FS semantics differ from the local APFS filesystem used in unit tests.
 */
class NormalizeSandboxTest extends SandboxTestBase {

    private static final String CODE = "MIDE-123";

    TitleNormalizerService service;
    Path normalizeDir;

    @BeforeEach
    void setUpService() throws Exception {
        service = new TitleNormalizerService(config.media(), config.normalize());
        normalizeDir = methodRunDir.resolve("normalize");
        fs.createDirectories(normalizeDir);
    }

    @Test
    void filesRenamedToCanonical() throws Exception {
        // mide123pl.jpg / mide123.mp4 — no dash in code, not case-insensitively equal to target
        Path titleDir = new SandboxTitleBuilder()
                .inDir(normalizeDir)
                .withCode(CODE)
                .withCover("mide123pl.jpg")
                .withVideo("mide123.mp4")
                .build(fs);

        TitleNormalizerService.Result result = service.apply(fs, titleDir, CODE, false);

        assertFalse(result.applied().isEmpty(), "Expected at least one rename to be applied");
        assertTrue(result.failed().isEmpty(), "No renames should fail");

        List<Path> after = fs.listDirectory(titleDir);
        assertTrue(after.stream().anyMatch(p -> p.getFileName().toString().equalsIgnoreCase("MIDE-123.jpg")),
                "Cover should be renamed to MIDE-123.jpg, got: " + after);
    }

    @Test
    void alreadyNormalized_noOp() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(normalizeDir)
                .withCode(CODE)
                .withCover("MIDE-123.jpg")
                .withVideo("MIDE-123.mp4")
                .build(fs);

        TitleNormalizerService.Result result = service.apply(fs, titleDir, CODE, false);

        assertTrue(result.applied().isEmpty(), "No renames should be applied when already normalized");
        assertEquals(2, result.skipped().size(), "Both cover and video should be skipped as already canonical");
    }

    @Test
    void multipleCovers_coverSkipped_videoRenamed() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(normalizeDir)
                .withCode(CODE)
                .withCover("cover1.jpg")
                .withExtraCover("cover2.jpg")
                .withVideo("mide123.mp4")
                .build(fs);

        TitleNormalizerService.Result result = service.apply(fs, titleDir, CODE, false);

        assertTrue(result.skipped().stream().anyMatch(s -> s.kind().equals("cover")),
                "Cover should be skipped when multiple covers exist at base");
        assertTrue(result.applied().stream().anyMatch(a -> a.op().equals("video-rename"))
                        || result.skipped().stream().anyMatch(s -> s.kind().equals("video")),
                "Video should be renamed or reported");
    }

    @Test
    void multipleVideos_videoSkipped() throws Exception {
        // Two videos in the title's video/ subfolder → service defers (multi-file title).
        // Confirms the skip path fires correctly on real SMB where directory enumeration
        // order can differ from LocalFS.
        Path titleDir = new SandboxTitleBuilder()
                .inDir(normalizeDir)
                .withCode(CODE)
                .withCover("mide123pl.jpg")
                .withVideo("mide123.mp4")
                .build(fs);
        // Add a second video in the same subfolder
        fs.writeFile(titleDir.resolve("video").resolve("mide123-bonus.mp4"), new byte[]{1});

        TitleNormalizerService.Result result = service.apply(fs, titleDir, CODE, false);

        assertTrue(result.skipped().stream()
                        .anyMatch(s -> s.kind().equals("video") && s.reason().contains("multi-file")),
                "Video should be skipped as multi-file title, got skipped=" + result.skipped());
        // Cover is still renamed (single cover is unambiguous)
        assertTrue(result.applied().stream().anyMatch(a -> a.op().equals("cover-rename"))
                        || result.skipped().stream().anyMatch(s -> s.kind().equals("cover")),
                "Cover result should be reported");
    }

    @Test
    void dryRunDoesNotMutate() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(normalizeDir)
                .withCode(CODE)
                .withCover("mide123pl.jpg")
                .withVideo("mide123.mp4")
                .build(fs);

        TitleNormalizerService.Result result = service.apply(fs, titleDir, CODE, true);

        assertTrue(result.dryRun(), "Result should indicate dry run");
        assertFalse(result.planned().isEmpty(), "Should report changes needed");
        assertTrue(result.applied().isEmpty(), "Dry run must not apply any changes");

        List<Path> after = fs.listDirectory(titleDir);
        assertTrue(after.stream().anyMatch(p -> p.getFileName().toString().equals("mide123pl.jpg")),
                "Original cover filename must still exist after dry run, got: " + after);
    }
}
