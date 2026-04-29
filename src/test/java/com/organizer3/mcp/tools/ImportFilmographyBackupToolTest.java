package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.enrichment.FetchResult;
import com.organizer3.javdb.enrichment.FilmographyBackupWriter;
import com.organizer3.javdb.enrichment.FilmographyEntry;
import com.organizer3.javdb.enrichment.JdbiJavdbActressFilmographyRepository;
import com.organizer3.javdb.enrichment.JavdbActressFilmographyRepository;
import com.organizer3.javdb.enrichment.JavdbFilmographyParser;
import com.organizer3.javdb.JavdbSearchParser;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.javdb.JavdbClient;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportFilmographyBackupToolTest {

    @TempDir Path tempDir;
    @TempDir Path tempDir2;   // separate dataDir for backup source

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JavdbActressFilmographyRepository repo;
    private JavdbSlugResolver resolver;
    private FilmographyBackupWriter backupWriter;
    private ImportFilmographyBackupTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiJavdbActressFilmographyRepository(jdbi);

        JavdbClient mockClient = mock(JavdbClient.class);
        backupWriter = new FilmographyBackupWriter(tempDir);
        resolver = new JavdbSlugResolver(mockClient, new JavdbFilmographyParser(),
                new JavdbSearchParser(), repo, backupWriter, 90, 10, Clock.systemUTC());

        tool = new ImportFilmographyBackupTool(backupWriter, repo, resolver);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private ObjectNode args(String archivePath) {
        ObjectNode n = M.createObjectNode();
        n.put("archive_path", archivePath);
        return n;
    }

    @Test
    void importArchive_populatesL2AndClearsL1() throws Exception {
        // Build a source archive with 2 actresses
        FilmographyBackupWriter sourceWriter = new FilmographyBackupWriter(tempDir2);
        sourceWriter.write("J9dd", new FetchResult("2026-01-01T00:00:00Z", 1, null, "http",
                List.of(new FilmographyEntry("STAR-334", "slug1"))));
        sourceWriter.write("Abcd", new FetchResult("2026-02-01T00:00:00Z", 2, null, "http",
                List.of(new FilmographyEntry("MANE-100", "slugA"))));
        Path archive = sourceWriter.archive();

        var result = (ImportFilmographyBackupTool.Result) tool.call(args(archive.toString()));

        assertEquals(2, result.actressesImported());

        // Verify L2 populated
        Map<String, String> j9dd = repo.getCodeToSlug("J9dd");
        assertEquals("slug1", j9dd.get("STAR-334"));

        Map<String, String> abcd = repo.getCodeToSlug("Abcd");
        assertEquals("slugA", abcd.get("MANE-100"));

        // Source marked as "imported_backup"
        assertEquals("imported_backup", repo.findMeta("J9dd").get().source());
    }

    @Test
    void importArchive_missingFile_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("/nonexistent/path/archive.zip")));
    }
}
