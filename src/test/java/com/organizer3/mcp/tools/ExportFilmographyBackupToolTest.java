package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.FetchResult;
import com.organizer3.javdb.enrichment.FilmographyBackupWriter;
import com.organizer3.javdb.enrichment.FilmographyEntry;
import com.organizer3.javdb.enrichment.JdbiJavdbActressFilmographyRepository;
import com.organizer3.javdb.enrichment.RevalidationPendingRepository;
import com.organizer3.javdb.enrichment.JavdbActressFilmographyRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExportFilmographyBackupToolTest {

    @TempDir Path tempDir;
    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JavdbActressFilmographyRepository repo;
    private FilmographyBackupWriter backupWriter;
    private ExportFilmographyBackupTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiJavdbActressFilmographyRepository(jdbi, new RevalidationPendingRepository(jdbi));
        backupWriter = new FilmographyBackupWriter(tempDir);
        tool = new ExportFilmographyBackupTool(backupWriter, repo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private ObjectNode args(String slug) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_slug", slug);
        return n;
    }

    @Test
    void readFromExistingBackupFile() throws Exception {
        // Write a backup file directly
        FetchResult result = new FetchResult("2026-01-01T00:00:00Z", 1, null, "http",
                List.of(new FilmographyEntry("STAR-334", "slug1")));
        backupWriter.write("J9dd", result);

        var r = (ExportFilmographyBackupTool.Result) tool.call(args("J9dd"));

        assertEquals("J9dd", r.actressSlug());
        assertEquals("backup_file", r.source());
        assertEquals(1, r.entryCount());
    }

    @Test
    void liveExportFromL2WhenNoBackupFile() {
        // L2 has data but no backup file
        repo.upsertFilmography("J9dd", new FetchResult("2026-01-01T00:00:00Z", 1, null, "http",
                List.of(new FilmographyEntry("STAR-334", "slug1"),
                        new FilmographyEntry("STAR-358", "slug2"))));

        var r = (ExportFilmographyBackupTool.Result) tool.call(args("J9dd"));

        assertEquals("live_export", r.source());
        assertEquals(2, r.entryCount());
    }

    @Test
    void throws_whenNoCachedDataExists() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args("nobody")));
    }
}
