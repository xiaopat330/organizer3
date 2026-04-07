package com.organizer3.config.alias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AliasLoader — YAML parsing of aliases.yaml entries.
 */
class AliasLoaderTest {

    @TempDir
    Path tempDir;

    private final AliasLoader loader = new AliasLoader();

    @Test
    void parsesMultipleEntries() throws IOException {
        Path file = write("""
                alias:
                  - name: Aya Sazanami
                    aliases:
                      - Haruka Suzumiya
                      - Aya Konami
                  - name: Hibiki Otsuki
                    aliases:
                      - Hibiki Ohtsuki
                """);

        List<AliasYamlEntry> entries = loader.load(file);

        assertEquals(2, entries.size());
        assertEquals("Aya Sazanami", entries.get(0).name());
        assertEquals(List.of("Haruka Suzumiya", "Aya Konami"), entries.get(0).aliases());
        assertEquals("Hibiki Otsuki", entries.get(1).name());
        assertEquals(List.of("Hibiki Ohtsuki"), entries.get(1).aliases());
    }

    @Test
    void parsesEntryWithNoAliases() throws IOException {
        Path file = write("""
                alias:
                  - name: Solo Actress
                    aliases: []
                """);

        List<AliasYamlEntry> entries = loader.load(file);

        assertEquals(1, entries.size());
        assertEquals("Solo Actress", entries.get(0).name());
        assertTrue(entries.get(0).aliases().isEmpty());
    }

    @Test
    void returnsEmptyListForEmptyAliasKey() throws IOException {
        Path file = write("""
                alias: []
                """);

        List<AliasYamlEntry> entries = loader.load(file);

        assertTrue(entries.isEmpty());
    }

    @Test
    void throwsIOExceptionForMissingFile() {
        Path missing = tempDir.resolve("nonexistent.yaml");
        assertThrows(IOException.class, () -> loader.load(missing));
    }

    @Test
    void parsesEntryWithSingleAlias() throws IOException {
        Path file = write("""
                alias:
                  - name: Maria Ozawa
                    aliases:
                      - Miyabi
                """);

        List<AliasYamlEntry> entries = loader.load(file);

        assertEquals(1, entries.size());
        assertEquals("Maria Ozawa", entries.get(0).name());
        assertEquals(List.of("Miyabi"), entries.get(0).aliases());
    }

    // --- Helpers ---

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("aliases.yaml");
        Files.writeString(file, content);
        return file;
    }
}
