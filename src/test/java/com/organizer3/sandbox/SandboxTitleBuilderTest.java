package com.organizer3.sandbox;

import com.organizer3.filesystem.LocalFileSystem;
import com.organizer3.filesystem.VolumeFileSystem;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTitleBuilderTest {

    @TempDir
    Path tempDir;

    VolumeFileSystem fs;

    @BeforeEach
    void setUp() {
        fs = new LocalFileSystem();
    }

    @Test
    void defaultLayout_createsTitleFolderWithCoverAndVideo() throws IOException {
        Path titleDir = new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .build(fs);

        assertEquals(tempDir.resolve("MIDE-123"), titleDir);
        assertTrue(Files.exists(titleDir.resolve("mide123pl.jpg")), "cover missing");
        assertTrue(Files.exists(titleDir.resolve("video/mide123.mp4")), "video missing");
    }

    @Test
    void customCoverAndVideo_usesSuppliedFilenames() throws IOException {
        new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("PRED-456")
                .withCover("pred456pl.jpg")
                .withVideo("pred456-custom.mp4")
                .build(fs);

        assertTrue(Files.exists(tempDir.resolve("PRED-456/pred456pl.jpg")));
        assertTrue(Files.exists(tempDir.resolve("PRED-456/video/pred456-custom.mp4")));
    }

    @Test
    void videoAtBase_placesVideoAtTitleRoot() throws IOException {
        new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .videoAtBase()
                .build(fs);

        assertTrue(Files.exists(tempDir.resolve("MIDE-123/mide123.mp4")), "video should be at root");
        assertFalse(Files.exists(tempDir.resolve("MIDE-123/video/")), "video/ subfolder should not exist");
    }

    @Test
    void videoInSubfolder_usesNamedSubfolder() throws IOException {
        new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .videoInSubfolder("h265")
                .build(fs);

        assertTrue(Files.exists(tempDir.resolve("MIDE-123/h265/mide123.mp4")));
    }

    @Test
    void withExtraCover_createsTwoCoversAtBase() throws IOException {
        new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .withCover("cover1.jpg")
                .withExtraCover("cover2.jpg")
                .build(fs);

        Path titleDir = tempDir.resolve("MIDE-123");
        List<Path> children = fs.listDirectory(titleDir);
        long coverCount = children.stream()
                .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                .count();
        assertEquals(2, coverCount, "expected 2 covers at base");
    }

    @Test
    void withTimestamp_appliesTimestampToFiles() throws IOException {
        Instant t = Instant.parse("2018-06-15T10:00:00Z");
        new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .withTimestamp(t)
                .build(fs);

        Path cover = tempDir.resolve("MIDE-123/mide123pl.jpg");
        Instant modified = fs.getTimestamps(cover).modified();
        assertNotNull(modified);
        // Local FS has millisecond precision; allow ±2s
        assertTrue(Math.abs(modified.getEpochSecond() - t.getEpochSecond()) <= 2,
                "Cover timestamp should be near " + t + " but was " + modified);
    }

    @Test
    void withTimestampOffset_subtractsOffsetFromBase() throws IOException {
        Instant base = Instant.parse("2023-01-01T12:00:00Z");
        new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .withTimestampOffset(base, 30)
                .build(fs);

        Instant expected = base.minusSeconds(30);
        Path cover = tempDir.resolve("MIDE-123/mide123pl.jpg");
        Instant actual = fs.getTimestamps(cover).modified();
        assertTrue(Math.abs(actual.getEpochSecond() - expected.getEpochSecond()) <= 2,
                "Expected timestamp near " + expected + " but was " + actual);
    }

    @Test
    void registerInDb_insertsTitleAndLocationRows() throws Exception {
        Connection dbConn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(dbConn);
        new com.organizer3.db.SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));

        Path titleDir = new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .build(fs);

        new SandboxTitleBuilder()
                .inDir(tempDir)
                .withCode("MIDE-123")
                .registerInDb(jdbi, titleDir, "vol-a", "library");

        int count = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_locations WHERE path = ?")
                        .bind(0, titleDir.toString())
                        .mapTo(Integer.class)
                        .one());
        assertEquals(1, count, "expected 1 title_locations row");

        dbConn.close();
    }
}
