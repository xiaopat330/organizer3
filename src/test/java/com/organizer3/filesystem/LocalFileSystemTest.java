package com.organizer3.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileSystemTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fs;

    @BeforeEach
    void setUp() {
        fs = new LocalFileSystem();
    }

    // --- listDirectory ---

    @Test
    void listDirectory_returnsImmediateChildren() throws IOException {
        Files.createFile(tempDir.resolve("a.txt"));
        Files.createFile(tempDir.resolve("b.txt"));
        Files.createDirectory(tempDir.resolve("sub"));

        List<Path> result = fs.listDirectory(tempDir);

        assertEquals(3, result.size());
        assertTrue(result.contains(tempDir.resolve("a.txt")));
        assertTrue(result.contains(tempDir.resolve("b.txt")));
        assertTrue(result.contains(tempDir.resolve("sub")));
    }

    @Test
    void listDirectory_doesNotDescendIntoSubdirectories() throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.createFile(sub.resolve("nested.txt"));

        List<Path> result = fs.listDirectory(tempDir);

        assertEquals(1, result.size());
        assertFalse(result.contains(sub.resolve("nested.txt")));
    }

    @Test
    void listDirectory_emptyDirectoryReturnsEmptyList() throws IOException {
        List<Path> result = fs.listDirectory(tempDir);
        assertTrue(result.isEmpty());
    }

    // --- walk ---

    @Test
    void walk_returnsAllPathsIncludingRoot() throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.createFile(tempDir.resolve("root.txt"));
        Files.createFile(sub.resolve("nested.txt"));

        List<Path> result = fs.walk(tempDir);

        assertTrue(result.contains(tempDir));
        assertTrue(result.contains(tempDir.resolve("root.txt")));
        assertTrue(result.contains(sub));
        assertTrue(result.contains(sub.resolve("nested.txt")));
        assertEquals(4, result.size());
    }

    // --- exists ---

    @Test
    void exists_returnsTrueForExistingFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("file.txt"));
        assertTrue(fs.exists(file));
    }

    @Test
    void exists_returnsFalseForMissingPath() {
        assertFalse(fs.exists(tempDir.resolve("does-not-exist.txt")));
    }

    // --- isDirectory ---

    @Test
    void isDirectory_returnsTrueForDirectory() {
        assertTrue(fs.isDirectory(tempDir));
    }

    @Test
    void isDirectory_returnsFalseForFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("file.txt"));
        assertFalse(fs.isDirectory(file));
    }

    // --- move ---

    @Test
    void move_relocatesFileToDestination() throws IOException {
        Path source = Files.createFile(tempDir.resolve("source.txt"));
        Path dest = tempDir.resolve("dest.txt");

        fs.move(source, dest);

        assertFalse(Files.exists(source));
        assertTrue(Files.exists(dest));
    }

    @Test
    void move_relocatesDirectoryToDestination() throws IOException {
        Path sourceDir = Files.createDirectory(tempDir.resolve("source-dir"));
        Files.createFile(sourceDir.resolve("inner.txt"));
        Path destDir = tempDir.resolve("dest-dir");

        fs.move(sourceDir, destDir);

        assertFalse(Files.exists(sourceDir));
        assertTrue(Files.exists(destDir));
        assertTrue(Files.exists(destDir.resolve("inner.txt")));
    }

    // --- rename ---

    @Test
    void rename_changesFilenameInSameDirectory() throws IOException {
        Path original = Files.createFile(tempDir.resolve("original.txt"));

        fs.rename(original, "renamed.txt");

        assertFalse(Files.exists(original));
        assertTrue(Files.exists(tempDir.resolve("renamed.txt")));
    }

    @Test
    void rename_changesDirectoryNameInSameParent() throws IOException {
        Path originalDir = Files.createDirectory(tempDir.resolve("old-name"));

        fs.rename(originalDir, "new-name");

        assertFalse(Files.exists(originalDir));
        assertTrue(Files.exists(tempDir.resolve("new-name")));
    }

    // --- getLastModifiedDate ---

    @Test
    void getLastModifiedDate_returnsDateForExistingFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("file.txt"));

        LocalDate result = fs.getLastModifiedDate(file);

        assertNotNull(result);
        assertEquals(LocalDate.now(), result);
    }

    // --- createDirectories ---

    @Test
    void createDirectories_createsSingleDirectory() throws IOException {
        Path newDir = tempDir.resolve("newdir");

        fs.createDirectories(newDir);

        assertTrue(Files.isDirectory(newDir));
    }

    @Test
    void createDirectories_createsNestedDirectories() throws IOException {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c");

        fs.createDirectories(nested);

        assertTrue(Files.isDirectory(nested));
    }

    @Test
    void createDirectories_isIdempotentWhenDirectoryAlreadyExists() throws IOException {
        Path existing = Files.createDirectory(tempDir.resolve("existing"));

        assertDoesNotThrow(() -> fs.createDirectories(existing));
        assertTrue(Files.isDirectory(existing));
    }

    // --- getTimestamps ---

    @Test
    void getTimestamps_returnsAllThreeForFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("file.txt"));
        FileTimestamps t = fs.getTimestamps(file);
        assertNotNull(t);
        assertNotNull(t.created());
        assertNotNull(t.modified());
        assertNotNull(t.accessed());
    }

    @Test
    void getTimestamps_worksForDirectories() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("d"));
        FileTimestamps t = fs.getTimestamps(dir);
        assertNotNull(t.modified());
    }

    // --- setTimestamps ---

    @Test
    void setTimestamps_updatesModifiedTime() throws IOException {
        Path file = Files.createFile(tempDir.resolve("f.txt"));
        java.time.Instant target = java.time.Instant.parse("2020-01-15T10:30:00Z");

        fs.setTimestamps(file, null, target);

        FileTimestamps after = fs.getTimestamps(file);
        assertEquals(target.toEpochMilli(), after.modified().toEpochMilli());
    }

    @Test
    void setTimestamps_updatesCreationTime() throws IOException {
        Path file = Files.createFile(tempDir.resolve("f.txt"));
        java.time.Instant target = java.time.Instant.parse("2018-06-01T00:00:00Z");

        fs.setTimestamps(file, target, null);

        FileTimestamps after = fs.getTimestamps(file);
        assertNotNull(after.created());
        assertEquals(target.toEpochMilli(), after.created().toEpochMilli());
    }

    @Test
    void setTimestamps_bothFieldsAtOnce() throws IOException {
        Path file = Files.createFile(tempDir.resolve("f.txt"));
        java.time.Instant created  = java.time.Instant.parse("2020-01-15T10:30:00Z");
        java.time.Instant modified = java.time.Instant.parse("2021-03-20T15:45:00Z");

        fs.setTimestamps(file, created, modified);

        FileTimestamps after = fs.getTimestamps(file);
        assertEquals(created.toEpochMilli(),  after.created().toEpochMilli());
        assertEquals(modified.toEpochMilli(), after.modified().toEpochMilli());
    }

    @Test
    void setTimestamps_worksOnDirectories() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("d"));
        java.time.Instant target = java.time.Instant.parse("2019-12-31T23:59:59Z");

        fs.setTimestamps(dir, target, target);

        FileTimestamps after = fs.getTimestamps(dir);
        assertEquals(target.toEpochMilli(), after.modified().toEpochMilli());
    }

    @Test
    void setTimestamps_nullArgsLeaveTimestampsAlone() throws IOException {
        Path file = Files.createFile(tempDir.resolve("f.txt"));
        FileTimestamps before = fs.getTimestamps(file);

        fs.setTimestamps(file, null, null);

        FileTimestamps after = fs.getTimestamps(file);
        assertEquals(before.modified().toEpochMilli(), after.modified().toEpochMilli());
        if (before.created() != null && after.created() != null) {
            assertEquals(before.created().toEpochMilli(), after.created().toEpochMilli());
        }
    }
}
