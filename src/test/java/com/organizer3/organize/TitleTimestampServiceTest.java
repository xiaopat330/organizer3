package com.organizer3.organize;

import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.LocalFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TitleTimestampServiceTest {

    @TempDir Path tempDir;

    private LocalFileSystem fs;
    private TitleTimestampService svc;

    @BeforeEach
    void setUp() {
        fs = new LocalFileSystem();
        svc = new TitleTimestampService();
    }

    @Test
    void dryRun_reportsChangeButDoesNotTouchFolder() throws IOException {
        Path titleFolder = Files.createDirectory(tempDir.resolve("MIDE-123"));
        Path cover = Files.createFile(titleFolder.resolve("mide123pl.jpg"));
        Instant oldTime = Instant.parse("2018-06-15T10:00:00Z");
        fs.setTimestamps(cover, oldTime, oldTime);

        FileTimestamps folderBefore = fs.getTimestamps(titleFolder);
        TitleTimestampService.Result r = svc.apply(fs, titleFolder, true);

        assertTrue(r.dryRun());
        assertFalse(r.applied());
        assertTrue(r.plan().needsChange(), "folder time differs from child time, should need change");
        assertEquals(oldTime.toEpochMilli(), r.plan().earliestChildTime().toEpochMilli());

        FileTimestamps folderAfter = fs.getTimestamps(titleFolder);
        assertEquals(folderBefore.modified().toEpochMilli(), folderAfter.modified().toEpochMilli(),
                "dry-run must leave folder modification time unchanged");
    }

    @Test
    void execute_setsFolderToEarliestChildTime() throws IOException {
        Path titleFolder = Files.createDirectory(tempDir.resolve("MIDE-123"));
        Path cover = Files.createFile(titleFolder.resolve("mide123pl.jpg"));
        Path video = Files.createFile(titleFolder.resolve("mide123.mp4"));
        Instant coverTime = Instant.parse("2018-06-15T10:00:00Z");
        Instant videoTime = Instant.parse("2019-01-20T08:00:00Z");  // later
        fs.setTimestamps(cover, coverTime, coverTime);
        fs.setTimestamps(video, videoTime, videoTime);

        TitleTimestampService.Result r = svc.apply(fs, titleFolder, false);

        assertFalse(r.dryRun());
        assertTrue(r.applied());
        assertNull(r.error());
        assertEquals(coverTime.toEpochMilli(), r.plan().earliestChildTime().toEpochMilli());

        FileTimestamps folderAfter = fs.getTimestamps(titleFolder);
        assertEquals(coverTime.toEpochMilli(), folderAfter.modified().toEpochMilli());
        assertEquals(coverTime.toEpochMilli(), folderAfter.created().toEpochMilli());
    }

    @Test
    void noChangeWhenFolderAlreadyMatchesEarliestChild() throws IOException {
        Path titleFolder = Files.createDirectory(tempDir.resolve("MIDE-123"));
        Path cover = Files.createFile(titleFolder.resolve("mide123pl.jpg"));
        Instant t = Instant.parse("2018-06-15T10:00:00Z");
        fs.setTimestamps(cover, t, t);
        fs.setTimestamps(titleFolder, t, t);

        TitleTimestampService.Result r = svc.apply(fs, titleFolder, false);

        assertFalse(r.plan().needsChange());
        assertFalse(r.applied());
    }

    @Test
    void emptyFolder_returnsNoEarliestAndNoChange() throws IOException {
        Path titleFolder = Files.createDirectory(tempDir.resolve("EMPTY"));

        TitleTimestampService.Result r = svc.apply(fs, titleFolder, false);

        assertNull(r.plan().earliestChildTime());
        assertFalse(r.plan().needsChange());
        assertFalse(r.applied());
    }

    @Test
    void rejectsMissingTitleFolder() {
        Path missing = tempDir.resolve("nope");
        assertThrows(IllegalArgumentException.class,
                () -> svc.apply(fs, missing, true));
    }

    @Test
    void rejectsFileArgument() throws IOException {
        Path file = Files.createFile(tempDir.resolve("not-a-folder.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.apply(fs, file, true));
    }

    @Test
    void picksEarliestBetweenCreatedAndModified() throws IOException {
        Path titleFolder = Files.createDirectory(tempDir.resolve("T"));
        Path child = Files.createFile(titleFolder.resolve("c.jpg"));
        Instant created  = Instant.parse("2018-01-01T00:00:00Z");
        Instant modified = Instant.parse("2020-06-15T12:00:00Z");
        fs.setTimestamps(child, created, modified);

        TitleTimestampService.Result r = svc.apply(fs, titleFolder, true);

        assertEquals(created.toEpochMilli(), r.plan().earliestChildTime().toEpochMilli());
    }

    @Test
    void picksModifiedWhenEarlierThanCreated_nasBatchCopyCase() throws IOException {
        // Simulates what we see on the real NAS: files batch-copied in 2020 have
        // creation=2020 on the NAS but their preserved modified=2014 reflects the
        // original authoring time. The modified time is the more reliable catalog-era signal.
        Path titleFolder = Files.createDirectory(tempDir.resolve("T"));
        Path cover = Files.createFile(titleFolder.resolve("cover.jpg"));
        Instant batchCopiedAt = Instant.parse("2020-11-21T05:13:53Z");
        Instant authoredAt    = Instant.parse("2014-10-12T15:19:10Z");  // earlier
        fs.setTimestamps(cover, batchCopiedAt, authoredAt);

        TitleTimestampService.Result r = svc.apply(fs, titleFolder, true);

        assertEquals(authoredAt.toEpochMilli(), r.plan().earliestChildTime().toEpochMilli(),
                "should pick the earlier modified time, not the later creation time");
    }

    @Test
    void picksAbsoluteMinAcrossMultipleChildren() throws IOException {
        Path titleFolder = Files.createDirectory(tempDir.resolve("T"));
        Path a = Files.createFile(titleFolder.resolve("a.jpg"));
        Path b = Files.createFile(titleFolder.resolve("b.mp4"));
        fs.setTimestamps(a, Instant.parse("2019-01-01T00:00:00Z"), Instant.parse("2018-06-01T00:00:00Z"));
        fs.setTimestamps(b, Instant.parse("2017-03-15T00:00:00Z"), Instant.parse("2020-08-20T00:00:00Z"));

        TitleTimestampService.Result r = svc.apply(fs, titleFolder, true);

        // earliest across all (created, modified) pairs = b.created = 2017-03-15
        assertEquals(Instant.parse("2017-03-15T00:00:00Z").toEpochMilli(),
                r.plan().earliestChildTime().toEpochMilli());
    }
}
