package com.organizer3.sandbox.organize;

import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.organize.TitleTimestampService;
import com.organizer3.sandbox.SandboxTestBase;
import com.organizer3.sandbox.SandboxTitleBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates {@link TitleTimestampService} over real SMB.
 *
 * <p>SMB-differentiating: timestamp round-trip — write an {@link Instant} with sub-second
 * precision, read it back, assert within ±1 second. Tests that SMB {@code FileBasicInformation}
 * write + read survives NAS-side rounding.
 *
 * <p>Timestamp fixtures use 10-second separation — well above the 2-second
 * {@code shouldChange} tolerance and any SMB-side rounding.
 */
class FixTimestampsSandboxTest extends SandboxTestBase {

    private static final String CODE = "MIDE-123";
    /** Cover timestamp: clearly in the past so it's earlier than the folder timestamp. */
    private static final Instant OLD_TIME = Instant.parse("2018-06-15T10:00:00Z");
    /** Folder timestamp: "now-ish", clearly later than OLD_TIME. */
    private static final Instant NEW_TIME = Instant.parse("2023-01-01T12:00:00Z");

    TitleTimestampService service;
    Path tsDir;

    @BeforeEach
    void setUpService() throws Exception {
        service = new TitleTimestampService();
        tsDir = methodRunDir.resolve("timestamps");
        fs.createDirectories(tsDir);
    }

    @Test
    void folderTimestampUpdatedToEarliestChild() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(tsDir)
                .withCode(CODE)
                .withTimestamp(OLD_TIME)
                .build(fs);
        // Set the folder timestamp to a more recent time so it differs
        fs.setTimestamps(titleDir, NEW_TIME, NEW_TIME);

        TitleTimestampService.Result result = service.apply(fs, titleDir, false);

        assertTrue(result.applied(), "Timestamp should have been applied");
        assertNull(result.error(), "No error expected, got: " + result.error());

        FileTimestamps after = fs.getTimestamps(titleDir);
        assertNotNull(after.created(), "created should be set");
        assertNotNull(after.modified(), "modified should be set");
        // SMB timestamps have 100ns resolution; assert within ±1 second
        assertWithinOneSecond(after.created(), OLD_TIME, "created");
        assertWithinOneSecond(after.modified(), OLD_TIME, "modified");
    }

    @Test
    void alreadyCorrect_noChange() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(tsDir)
                .withCode(CODE)
                .withTimestamp(OLD_TIME)
                .build(fs);
        // Align folder to OLD_TIME as well
        fs.setTimestamps(titleDir, OLD_TIME, OLD_TIME);

        TitleTimestampService.Result result = service.apply(fs, titleDir, false);

        assertFalse(result.plan().needsChange(), "Folder already matches; no change needed");
        assertFalse(result.applied(), "Should not apply when already correct");
    }

    @Test
    void emptyFolder_skipped() throws Exception {
        // Title folder with no files — earliest child time is null
        Path emptyTitle = tsDir.resolve("EMPTY-001");
        fs.createDirectories(emptyTitle);

        TitleTimestampService.Result result = service.apply(fs, emptyTitle, false);

        assertFalse(result.plan().needsChange(), "Empty folder has no child timestamps to use");
        assertFalse(result.applied(), "Should not apply on empty folder");
    }

    @Test
    void dryRunReportsButDoesNotApply() throws Exception {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(tsDir)
                .withCode(CODE)
                .withTimestamp(OLD_TIME)
                .build(fs);
        fs.setTimestamps(titleDir, NEW_TIME, NEW_TIME);

        TitleTimestampService.Result result = service.apply(fs, titleDir, true);

        assertTrue(result.dryRun(), "Result should indicate dry run");
        assertTrue(result.plan().needsChange(), "Should report change needed");
        assertFalse(result.applied(), "Dry run must not apply");

        // Folder timestamps must remain at NEW_TIME
        FileTimestamps after = fs.getTimestamps(titleDir);
        assertWithinOneSecond(after.modified(), NEW_TIME, "modified (should be unchanged)");
    }

    // -------------------------------------------------------------------------

    private static void assertWithinOneSecond(Instant actual, Instant expected, String label) {
        assertNotNull(actual, label + " was null");
        long diffSecs = Math.abs(actual.getEpochSecond() - expected.getEpochSecond());
        assertTrue(diffSecs <= 1,
                label + ": expected ~" + expected + " but got " + actual + " (diff=" + diffSecs + "s)");
    }
}
