package com.organizer3.covers;

import com.organizer3.model.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CoverPathTest {

    @TempDir
    Path tempDir;

    private CoverPath coverPath;

    @BeforeEach
    void setUp() {
        coverPath = new CoverPath(tempDir);
    }

    private Title title(String code, String baseCode, String label) {
        return Title.builder()
                .id(1L).code(code).baseCode(baseCode).label(label)
                .volumeId("a").partitionId("stars/popular").actressId(1L)
                .path(Path.of("/stars/popular/Actress/ABP-123"))
                .lastSeenAt(LocalDate.now()).addedDate(LocalDate.now())
                .build();
    }

    @Test
    void resolve_buildsCorrectPath() {
        Title t = title("ABP-123", "ABP-00123", "ABP");
        Path result = coverPath.resolve(t, "jpg");
        assertEquals(tempDir.resolve("data/covers/ABP/ABP-00123.jpg"), result);
    }

    @Test
    void resolve_uppercasesLabel() {
        Title t = title("abp-123", "ABP-00123", "abp");
        Path result = coverPath.resolve(t, "png");
        assertEquals(tempDir.resolve("data/covers/ABP/ABP-00123.png"), result);
    }

    @Test
    void find_returnsEmpty_whenNoDirectory() {
        Title t = title("ABP-123", "ABP-00123", "ABP");
        assertEquals(Optional.empty(), coverPath.find(t));
    }

    @Test
    void find_returnsCoverFile_whenExists() throws IOException {
        Title t = title("ABP-123", "ABP-00123", "ABP");
        Path dir = coverPath.labelDir(t);
        Files.createDirectories(dir);
        Path coverFile = dir.resolve("ABP-00123.jpg");
        Files.writeString(coverFile, "fake image");

        Optional<Path> result = coverPath.find(t);
        assertTrue(result.isPresent());
        assertEquals(coverFile, result.get());
    }

    @Test
    void find_ignoresNonImageFiles() throws IOException {
        Title t = title("ABP-123", "ABP-00123", "ABP");
        Path dir = coverPath.labelDir(t);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ABP-00123.txt"), "not an image");

        assertEquals(Optional.empty(), coverPath.find(t));
    }

    @Test
    void exists_returnsTrueWhenCoverPresent() throws IOException {
        Title t = title("ABP-123", "ABP-00123", "ABP");
        Path dir = coverPath.labelDir(t);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ABP-00123.webp"), "fake image");

        assertTrue(coverPath.exists(t));
    }

    @Test
    void exists_returnsFalseWhenNoCover() {
        Title t = title("ABP-123", "ABP-00123", "ABP");
        assertFalse(coverPath.exists(t));
    }

    @Test
    void isImageFile_recognizesImageExtensions() {
        assertTrue(CoverPath.isImageFile("cover.jpg"));
        assertTrue(CoverPath.isImageFile("cover.jpeg"));
        assertTrue(CoverPath.isImageFile("cover.png"));
        assertTrue(CoverPath.isImageFile("cover.webp"));
        assertTrue(CoverPath.isImageFile("cover.gif"));
        assertFalse(CoverPath.isImageFile("video.mp4"));
        assertFalse(CoverPath.isImageFile("readme.txt"));
        assertFalse(CoverPath.isImageFile("noextension"));
    }

    @Test
    void extensionOf_extractsExtension() {
        assertEquals("jpg", CoverPath.extensionOf("photo.JPG"));
        assertEquals("png", CoverPath.extensionOf("file.png"));
        assertEquals("", CoverPath.extensionOf("noext"));
    }
}
