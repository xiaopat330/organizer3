package com.organizer3.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StageNameBackupFileTest {

    @TempDir Path tempDir;

    @Test
    void saveCreatesFileWithCommentHeaderAndEntry() throws IOException {
        Path file = tempDir.resolve("stagenames.yaml");
        new StageNameBackupFile(file).save("Aya Sazanami", "佐々波 綾");

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.get(0).startsWith("#"), "first line should be a comment header");
        assertTrue(lines.contains("aya_sazanami: 佐々波 綾"));
    }

    @Test
    void saveUpdatesExistingKeyInPlace() throws IOException {
        Path file = tempDir.resolve("stagenames.yaml");
        var backup = new StageNameBackupFile(file);

        backup.save("Aya Sazanami", "first");
        backup.save("Aya Sazanami", "updated");

        List<String> lines = Files.readAllLines(file);
        long matching = lines.stream().filter(l -> l.startsWith("aya_sazanami:")).count();
        assertEquals(1, matching, "key should be stored once, not duplicated");
        assertTrue(lines.contains("aya_sazanami: updated"));
    }

    @Test
    void saveAppendsNewKeysAlongsideExistingEntries() throws IOException {
        Path file = tempDir.resolve("stagenames.yaml");
        var backup = new StageNameBackupFile(file);
        backup.save("Aya Sazanami", "a");
        backup.save("Yua Mikami", "y");

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.contains("aya_sazanami: a"));
        assertTrue(lines.contains("yua_mikami: y"));
    }

    @Test
    void saveSkipsBlankAndCommentLinesWhenReadingExistingFile() throws IOException {
        Path file = tempDir.resolve("stagenames.yaml");
        Files.write(file, List.of(
                "# header comment",
                "",
                "aya_sazanami: existing",
                "   ",  // blank-ish
                "not_a_valid_line_without_colon"
        ));

        new StageNameBackupFile(file).save("Yua Mikami", "y");

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.contains("aya_sazanami: existing"));
        assertTrue(lines.contains("yua_mikami: y"));
        // The malformed line is silently dropped by the parser.
        assertFalse(lines.contains("not_a_valid_line_without_colon"));
    }

    @Test
    void toKeyLowercasesAndReplacesSpacesWithUnderscores() {
        assertEquals("aya_sazanami", StageNameBackupFile.toKey("Aya Sazanami"));
        assertEquals("yua_mikami",   StageNameBackupFile.toKey("YUA MIKAMI"));
        assertEquals("single",       StageNameBackupFile.toKey("Single"));
    }

    @Test
    void ioErrorDuringSaveIsSwallowedAndLogged() {
        // Pointing at an unwritable path triggers the catch block. Should not throw.
        Path unwritable = tempDir.resolve("does/not/exist/file.yaml");
        var backup = new StageNameBackupFile(unwritable);
        assertDoesNotThrow(() -> backup.save("Aya", "a"));
        assertFalse(Files.exists(unwritable));
    }
}
