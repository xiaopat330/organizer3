package com.organizer3.command;

import com.organizer3.media.ThumbnailService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.PlainCommandIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClearThumbnailsCommandTest {

    @TempDir
    Path tempDir;

    private StringWriter output;
    private PlainCommandIO io;
    private SessionContext session;

    @BeforeEach
    void setUp() {
        output = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(output));
        session = new SessionContext();
    }

    @Test
    void clearsAllThumbnailDirectories() throws Exception {
        Path thumbRoot = tempDir.resolve("thumbnails");
        // Create two title dirs with thumbnails
        Path title1 = thumbRoot.resolve("ABP-123").resolve("video.mp4");
        Files.createDirectories(title1);
        Files.writeString(title1.resolve("thumb_01.jpg"), "data");
        Files.writeString(title1.resolve("thumb_02.jpg"), "data");

        Path title2 = thumbRoot.resolve("SSIS-456").resolve("clip.mkv");
        Files.createDirectories(title2);
        Files.writeString(title2.resolve("thumb_01.jpg"), "data");

        ThumbnailService service = new ThumbnailService(thumbRoot, 10, 8080);
        ClearThumbnailsCommand command = new ClearThumbnailsCommand(service);

        command.execute(new String[]{"clear-thumbnails"}, session, io);

        assertFalse(Files.exists(thumbRoot.resolve("ABP-123")));
        assertFalse(Files.exists(thumbRoot.resolve("SSIS-456")));
        assertTrue(Files.exists(thumbRoot)); // root dir itself stays
        assertTrue(output.toString().contains("Cleared"));
        assertTrue(output.toString().contains("2 titles"));
    }

    @Test
    void handlesEmptyThumbnailDirectory() throws Exception {
        Path thumbRoot = tempDir.resolve("thumbnails");
        Files.createDirectories(thumbRoot);

        ThumbnailService service = new ThumbnailService(thumbRoot, 10, 8080);
        ClearThumbnailsCommand command = new ClearThumbnailsCommand(service);

        command.execute(new String[]{"clear-thumbnails"}, session, io);

        assertTrue(output.toString().contains("Cleared 0 thumbnails across 0 titles"));
    }

    @Test
    void handlesNonexistentDirectory() {
        Path thumbRoot = tempDir.resolve("nonexistent");

        ThumbnailService service = new ThumbnailService(thumbRoot, 10, 8080);
        ClearThumbnailsCommand command = new ClearThumbnailsCommand(service);

        command.execute(new String[]{"clear-thumbnails"}, session, io);

        assertTrue(output.toString().contains("nothing to clear"));
    }

    @Test
    void nameAndDescription() {
        ThumbnailService service = new ThumbnailService(tempDir, 10, 8080);
        ClearThumbnailsCommand command = new ClearThumbnailsCommand(service);

        assertEquals("clear-thumbnails", command.name());
        assertNotNull(command.description());
    }
}
