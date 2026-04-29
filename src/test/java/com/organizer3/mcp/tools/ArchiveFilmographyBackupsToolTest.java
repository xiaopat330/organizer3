package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.enrichment.FetchResult;
import com.organizer3.javdb.enrichment.FilmographyBackupWriter;
import com.organizer3.javdb.enrichment.FilmographyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveFilmographyBackupsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void archive_createsZipAndReturnsSlugCount() throws Exception {
        FilmographyBackupWriter writer = new FilmographyBackupWriter(tempDir);
        writer.write("J9dd", new FetchResult("2026-01-01T00:00:00Z", 1, null, "http",
                List.of(new FilmographyEntry("STAR-334", "slug1"))));
        writer.write("Abcd", new FetchResult("2026-01-01T00:00:00Z", 1, null, "http", List.of()));

        ArchiveFilmographyBackupsTool tool = new ArchiveFilmographyBackupsTool(writer);
        var result = (ArchiveFilmographyBackupsTool.Result) tool.call(M.createObjectNode());

        assertEquals(2, result.slugsArchived());
        assertTrue(Files.exists(Path.of(result.archivePath())));
        assertTrue(result.archivePath().endsWith(".zip"));
    }

    @Test
    void archive_emptyBackupDir_producesEmptyZip() throws Exception {
        ArchiveFilmographyBackupsTool tool = new ArchiveFilmographyBackupsTool(new FilmographyBackupWriter(tempDir));
        var result = (ArchiveFilmographyBackupsTool.Result) tool.call(M.createObjectNode());

        assertEquals(0, result.slugsArchived());
        assertTrue(Files.exists(Path.of(result.archivePath())));
    }
}
