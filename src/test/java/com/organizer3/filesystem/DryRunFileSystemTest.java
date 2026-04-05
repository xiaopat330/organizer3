package com.organizer3.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DryRunFileSystemTest {

    @TempDir
    Path tempDir;

    private StringWriter output;
    private DryRunFileSystem fs;

    @BeforeEach
    void setUp() {
        output = new StringWriter();
        fs = new DryRunFileSystem(new PrintWriter(output));
    }

    // --- Read operations delegate to real filesystem ---

    @Test
    void listDirectory_returnsRealDirectoryContents() throws IOException {
        Files.createFile(tempDir.resolve("a.txt"));
        Files.createFile(tempDir.resolve("b.txt"));

        List<Path> result = fs.listDirectory(tempDir);

        assertEquals(2, result.size());
        assertTrue(result.contains(tempDir.resolve("a.txt")));
        assertTrue(result.contains(tempDir.resolve("b.txt")));
    }

    @Test
    void walk_returnsRealTreeContents() throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.createFile(sub.resolve("file.txt"));

        List<Path> result = fs.walk(tempDir);

        assertTrue(result.contains(tempDir));
        assertTrue(result.contains(sub));
        assertTrue(result.contains(sub.resolve("file.txt")));
    }

    @Test
    void exists_reflectsRealFilesystem() throws IOException {
        Path file = Files.createFile(tempDir.resolve("real.txt"));
        assertTrue(fs.exists(file));
        assertFalse(fs.exists(tempDir.resolve("ghost.txt")));
    }

    @Test
    void isDirectory_reflectsRealFilesystem() throws IOException {
        Path file = Files.createFile(tempDir.resolve("file.txt"));
        assertTrue(fs.isDirectory(tempDir));
        assertFalse(fs.isDirectory(file));
    }

    // --- Write operations log output and do NOT touch the filesystem ---

    @Test
    void move_logsIntentionAndDoesNotMoveFile() throws IOException {
        Path source = Files.createFile(tempDir.resolve("source.txt"));
        Path dest = tempDir.resolve("dest.txt");

        fs.move(source, dest);

        assertTrue(Files.exists(source), "source must still exist");
        assertFalse(Files.exists(dest), "dest must not be created");
        String log = output.toString();
        assertTrue(log.contains("[DRY RUN]"));
        assertTrue(log.contains(source.toString()));
        assertTrue(log.contains(dest.toString()));
    }

    @Test
    void rename_logsIntentionAndDoesNotRenameFile() throws IOException {
        Path original = Files.createFile(tempDir.resolve("original.txt"));

        fs.rename(original, "renamed.txt");

        assertTrue(Files.exists(original), "original must still exist");
        assertFalse(Files.exists(tempDir.resolve("renamed.txt")), "renamed file must not be created");
        String log = output.toString();
        assertTrue(log.contains("[DRY RUN]"));
        assertTrue(log.contains("original.txt"));
        assertTrue(log.contains("renamed.txt"));
    }

    @Test
    void createDirectories_logsIntentionAndDoesNotCreateDirectory() {
        Path newDir = tempDir.resolve("new-dir");

        fs.createDirectories(newDir);

        assertFalse(Files.exists(newDir), "directory must not be created");
        String log = output.toString();
        assertTrue(log.contains("[DRY RUN]"));
        assertTrue(log.contains(newDir.toString()));
    }

    @Test
    void multipleWriteOps_eachAppearsInLog() throws IOException {
        Path file = Files.createFile(tempDir.resolve("file.txt"));
        Path dest = tempDir.resolve("moved.txt");
        Path newDir = tempDir.resolve("new-dir");

        fs.move(file, dest);
        fs.rename(file, "renamed.txt");
        fs.createDirectories(newDir);

        String log = output.toString();
        long dryRunCount = log.lines().filter(l -> l.contains("[DRY RUN]")).count();
        assertEquals(3, dryRunCount);
    }
}
