package com.organizer3.avstars.cleanup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AvArtifactCleanerTest {

    @TempDir Path tmp;
    private Path screenshotDir;
    private Path headshotDir;
    private AvArtifactCleaner cleaner;

    @BeforeEach
    void setup() throws IOException {
        screenshotDir = Files.createDirectory(tmp.resolve("av_screenshots"));
        headshotDir = Files.createDirectory(tmp.resolve("av_headshots"));
        cleaner = new AvArtifactCleaner(screenshotDir, headshotDir);
    }

    @Test
    void deletesScreenshotDirectoryTreesByVideoId() throws IOException {
        Path v1 = Files.createDirectory(screenshotDir.resolve("1"));
        Files.writeString(v1.resolve("001.jpg"), "x");
        Files.writeString(v1.resolve("002.jpg"), "y");
        Path v2 = Files.createDirectory(screenshotDir.resolve("2"));
        Files.writeString(v2.resolve("001.jpg"), "z");

        int removed = cleaner.deleteScreenshotsFor(List.of(1L, 2L));
        assertEquals(2, removed);
        assertFalse(Files.exists(v1));
        assertFalse(Files.exists(v2));
    }

    @Test
    void skipsMissingScreenshotDirectories() {
        int removed = cleaner.deleteScreenshotsFor(List.of(999L));
        assertEquals(0, removed);
    }

    @Test
    void deleteHeadshotHandlesBareFilename() throws IOException {
        Path f = headshotDir.resolve("abc-123.jpg");
        Files.writeString(f, "x");
        assertTrue(cleaner.deleteHeadshot("abc-123.jpg"));
        assertFalse(Files.exists(f));
    }

    @Test
    void deleteHeadshotStripsLeadingPathComponents() throws IOException {
        // The stored value may include a relative path; the cleaner should take the filename only.
        Path f = headshotDir.resolve("uuid-1234.jpg");
        Files.writeString(f, "x");
        assertTrue(cleaner.deleteHeadshot("subdir/uuid-1234.jpg"));
        assertFalse(Files.exists(f));
    }

    @Test
    void deleteHeadshotHandlesNullAndBlank() {
        assertFalse(cleaner.deleteHeadshot(null));
        assertFalse(cleaner.deleteHeadshot(""));
        assertFalse(cleaner.deleteHeadshot("   "));
    }

    @Test
    void deleteHeadshotReturnsFalseWhenFileMissing() {
        assertFalse(cleaner.deleteHeadshot("does-not-exist.jpg"));
    }
}
