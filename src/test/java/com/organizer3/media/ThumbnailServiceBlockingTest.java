package com.organizer3.media;

import com.organizer3.model.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the background-sync additions to ThumbnailService (no FFmpeg involved). */
class ThumbnailServiceBlockingTest {

    @TempDir Path root;
    private ThumbnailService svc;

    @BeforeEach
    void setUp() {
        svc = new ThumbnailService(root, 8, 8080);
    }

    @Test
    void isCompleteTrueWhenAllThumbsPresent() throws Exception {
        Path videoDir = root.resolve("ABP-001").resolve("v.mp4");
        Files.createDirectories(videoDir);
        Files.writeString(videoDir.resolve(".count"), "3");
        Files.writeString(videoDir.resolve("thumb_01.jpg"), "x");
        Files.writeString(videoDir.resolve("thumb_02.jpg"), "x");
        Files.writeString(videoDir.resolve("thumb_03.jpg"), "x");

        assertTrue(svc.isComplete("ABP-001", "v.mp4", 42L));
    }

    @Test
    void isCompleteFalseWhenCountMissing() {
        assertFalse(svc.isComplete("NOPE-001", "v.mp4", 1L));
    }

    @Test
    void isCompleteFalseWhenThumbsPartial() throws Exception {
        Path videoDir = root.resolve("PART-001").resolve("v.mp4");
        Files.createDirectories(videoDir);
        Files.writeString(videoDir.resolve(".count"), "3");
        Files.writeString(videoDir.resolve("thumb_01.jpg"), "x");
        // only one of three

        assertFalse(svc.isComplete("PART-001", "v.mp4", 1L));
    }

    @Test
    void generateBlockingShortCircuitsWhenComplete() throws Exception {
        Path videoDir = root.resolve("DONE-001").resolve("v.mp4");
        Files.createDirectories(videoDir);
        Files.writeString(videoDir.resolve(".count"), "1");
        Files.writeString(videoDir.resolve("thumb_01.jpg"), "x");

        Video v = Video.builder()
                .id(7L).titleId(1L).volumeId("vol-a")
                .filename("v.mp4").path(Path.of("/x/v.mp4"))
                .lastSeenAt(LocalDate.now()).build();

        // Must return false (skipped) without hitting FFmpeg — if it tried to grab
        // it would fail loudly on the fake stream URL. A clean "false" proves the
        // short-circuit fired.
        assertFalse(svc.generateBlocking("DONE-001", v),
                "generateBlocking should skip when already complete");
    }
}
