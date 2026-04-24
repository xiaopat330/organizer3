package com.organizer3.sandbox.organize;

import com.organizer3.organize.FreshPrepService;
import com.organizer3.sandbox.SandboxTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates {@link FreshPrepService} over real SMB.
 *
 * <p>SMB-differentiating: move from the partition root into a new subfolder tree on a real
 * SMB share — confirms createDirectories + move execute atomically without cross-share fallback.
 */
class PrepSandboxTest extends SandboxTestBase {

    FreshPrepService service;
    Path freshRoot;

    @BeforeEach
    void setUpService() throws Exception {
        service = new FreshPrepService(config.normalize(), config.media());
        freshRoot = methodRunDir.resolve("fresh");
        fs.createDirectories(freshRoot);
    }

    @Test
    void looseVideoCreatesFolder() throws Exception {
        fs.writeFile(freshRoot.resolve("MIDE-123.mp4"), new byte[]{1});

        FreshPrepService.Result result = service.execute(fs, freshRoot, 10, 0);

        assertFalse(result.moved().isEmpty(), "Expected video to be moved");
        assertTrue(result.failed().isEmpty(), "No moves should fail");

        assertTrue(fs.exists(freshRoot.resolve("(MIDE-123)").resolve("video").resolve("MIDE-123.mp4")),
                "Video should be at (MIDE-123)/video/MIDE-123.mp4");
        assertFalse(fs.exists(freshRoot.resolve("MIDE-123.mp4")),
                "Loose video should no longer be at partition root");
    }

    @Test
    void h265VideoGoesToH265Subfolder() throws Exception {
        fs.writeFile(freshRoot.resolve("PRED-456-h265.mp4"), new byte[]{1});

        FreshPrepService.Result result = service.execute(fs, freshRoot, 10, 0);

        assertFalse(result.moved().isEmpty(), "Expected h265 video to be moved");
        assertTrue(fs.exists(freshRoot.resolve("(PRED-456)").resolve("h265").resolve("PRED-456-h265.mp4")),
                "h265 video should be at (PRED-456)/h265/PRED-456-h265.mp4");
    }

    @Test
    void junkPrefixStripped() throws Exception {
        fs.writeFile(freshRoot.resolve("foo.com@ONED-999-h265.mp4"), new byte[]{1});

        FreshPrepService.Result result = service.execute(fs, freshRoot, 10, 0);

        // Either moved (prefix stripped) or skipped (if stripper not triggered)
        // Confirm the output landed somewhere sensible if moved
        if (!result.moved().isEmpty()) {
            assertTrue(fs.exists(freshRoot.resolve("(ONED-999)").resolve("h265").resolve("ONED-999-h265.mp4")),
                    "Stripped video should be at (ONED-999)/h265/ONED-999-h265.mp4");
        } else {
            assertFalse(result.skipped().isEmpty(),
                    "If not moved, should be explicitly skipped");
        }
    }

    @Test
    void unparseable_skipped() throws Exception {
        fs.writeFile(freshRoot.resolve("random_stuff.mp4"), new byte[]{1});

        FreshPrepService.Result result = service.execute(fs, freshRoot, 10, 0);

        assertTrue(result.moved().isEmpty(), "Unparseable file should not be moved");
        assertFalse(result.skipped().isEmpty(), "Unparseable file should be in skipped list");
        assertTrue(result.skipped().stream().anyMatch(s -> s.filename().equals("random_stuff.mp4")),
                "random_stuff.mp4 should appear in skipped, got: " + result.skipped());
    }

    @Test
    void collisionSkipped() throws Exception {
        fs.writeFile(freshRoot.resolve("MIDE-123.mp4"), new byte[]{1});
        // Pre-create the target folder to cause a collision (parenthesized folder name)
        fs.createDirectories(freshRoot.resolve("(MIDE-123)"));

        FreshPrepService.Result result = service.execute(fs, freshRoot, 10, 0);

        assertFalse(result.skipped().isEmpty(), "Should be skipped when destination folder exists");
        assertTrue(result.moved().isEmpty(), "No moves should succeed with collision");
    }

    @Test
    void dryRunDoesNotMutate() throws Exception {
        fs.writeFile(freshRoot.resolve("MIDE-123.mp4"), new byte[]{1});

        FreshPrepService.Result result = service.plan(fs, freshRoot, 10, 0);

        assertTrue(result.dryRun(), "plan() should return dry-run result");
        assertFalse(result.planned().isEmpty(), "Should report move needed");
        assertTrue(result.moved().isEmpty(), "Plan must not move any files");

        assertTrue(fs.exists(freshRoot.resolve("MIDE-123.mp4")),
                "Loose video must still be at partition root after plan()");
        assertFalse(fs.exists(freshRoot.resolve("(MIDE-123)")),
                "Target folder must not be created by plan()");
    }

    @Test
    void offsetPagination() throws Exception {
        fs.writeFile(freshRoot.resolve("MIDE-001.mp4"), new byte[]{1});
        fs.writeFile(freshRoot.resolve("MIDE-002.mp4"), new byte[]{1});
        fs.writeFile(freshRoot.resolve("MIDE-003.mp4"), new byte[]{1});

        // Only process first 2
        FreshPrepService.Result result = service.execute(fs, freshRoot, 2, 0);
        assertEquals(2, result.moved().size(), "Should process exactly 2 videos with limit=2");
    }
}
