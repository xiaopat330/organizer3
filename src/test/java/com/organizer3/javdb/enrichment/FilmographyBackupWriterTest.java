package com.organizer3.javdb.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilmographyBackupWriterTest {

    @TempDir Path tempDir;

    private FilmographyBackupWriter writer() {
        return new FilmographyBackupWriter(tempDir);
    }

    private static FetchResult sampleResult(String... codeSlugPairs) {
        List<FilmographyEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < codeSlugPairs.length; i += 2) {
            entries.add(new FilmographyEntry(codeSlugPairs[i], codeSlugPairs[i + 1]));
        }
        return new FetchResult("2026-01-01T00:00:00Z", 2, null, "http", entries);
    }

    @Test
    void roundTrip_writeThenRead() throws IOException {
        var w = writer();
        FetchResult result = sampleResult("STAR-334", "slug1", "STAR-358", "slug2");

        w.write("J9dd", result);
        FilmographyBackupWriter.Envelope env = w.read("J9dd");

        assertNotNull(env);
        assertEquals("J9dd", env.actressSlug());
        assertEquals("2026-01-01T00:00:00Z", env.fetchedAt());
        assertEquals(2, env.pageCount());
        assertNull(env.lastReleaseDate());
        assertEquals("http", env.source());
        assertEquals(2, env.entries().size());
    }

    @Test
    void sharding_usesFirst2CharsOfSlug() throws IOException {
        var w = writer();
        w.write("J9dd", sampleResult());
        // File should be under backups/filmography/J9/J9dd.json
        Path expected = tempDir.resolve("backups/filmography/J9/J9dd.json");
        assertTrue(Files.exists(expected), "sharded file must exist at " + expected);
    }

    @Test
    void write_isIdempotent_overwritesPrior() throws IOException {
        var w = writer();
        w.write("J9dd", sampleResult("STAR-334", "old-slug"));
        w.write("J9dd", sampleResult("STAR-334", "new-slug"));

        FilmographyBackupWriter.Envelope env = w.read("J9dd");
        assertNotNull(env);
        assertEquals(1, env.entries().size());
        assertEquals("new-slug", env.entries().get(0).titleSlug());
    }

    @Test
    void read_returnsNullWhenNoBackupExists() throws IOException {
        assertNull(writer().read("nobody"));
    }

    @Test
    void listBackedUpSlugs_returnsAllWrittenSlugs() throws IOException {
        var w = writer();
        w.write("J9dd", sampleResult());
        w.write("J9xy", sampleResult());
        w.write("Abcd", sampleResult());

        List<String> slugs = w.listBackedUpSlugs();
        assertTrue(slugs.contains("J9dd"));
        assertTrue(slugs.contains("J9xy"));
        assertTrue(slugs.contains("Abcd"));
        assertEquals(3, slugs.size());
    }

    @Test
    void listBackedUpSlugs_emptyWhenNothingWritten() throws IOException {
        assertTrue(writer().listBackedUpSlugs().isEmpty());
    }

    @Test
    void archive_createsZipContainingAllFiles() throws IOException {
        var w = writer();
        w.write("J9dd", sampleResult("STAR-334", "slug1"));
        w.write("Abcd", sampleResult("MANE-100", "slug2"));

        Path archive = w.archive();

        assertTrue(Files.exists(archive));
        assertTrue(archive.getFileName().toString().startsWith("filmography-archive-"));
        assertTrue(archive.getFileName().toString().endsWith(".zip"));

        // Verify zip contents
        try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            int count = 0;
            while (zis.getNextEntry() != null) count++;
            assertEquals(2, count);
        }
    }

    @Test
    void shortSlug_usesFullSlugAsPrefix() throws IOException {
        // Slug with only 1 character — prefix = slug itself
        var w = writer();
        w.write("X", sampleResult());
        Path expected = tempDir.resolve("backups/filmography/X/X.json");
        assertTrue(Files.exists(expected));
    }
}
