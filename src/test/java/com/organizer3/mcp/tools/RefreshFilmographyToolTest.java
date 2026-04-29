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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshFilmographyToolTest {

    @TempDir Path tempDir;

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JavdbActressFilmographyRepository repo;
    private JavdbSlugResolver resolver;
    private RefreshFilmographyTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiJavdbActressFilmographyRepository(jdbi);

        // Pre-seed L2 with a cached filmography
        repo.upsertFilmography("J9dd", new FetchResult(
                "2026-01-01T00:00:00Z", 1, null, "http",
                List.of(new FilmographyEntry("STAR-334", "slug1"))));

        // Mock client that returns a different filmography on re-fetch
        JavdbClient mockClient = mock(JavdbClient.class);
        String pageHtml = "<html></html>"; // minimal — parser produces empty
        when(mockClient.fetchActressPage(eq("J9dd"), anyInt())).thenReturn(pageHtml);

        JavdbFilmographyParser mockParser = mock(JavdbFilmographyParser.class);
        var page = new com.organizer3.javdb.enrichment.FilmographyPage(
                List.of(new FilmographyEntry("STAR-334", "slug1-updated"),
                        new FilmographyEntry("STAR-999", "slug9")),
                false);
        when(mockParser.parsePage(any())).thenReturn(page);

        FilmographyBackupWriter backupWriter = new FilmographyBackupWriter(tempDir);
        resolver = new JavdbSlugResolver(mockClient, mockParser, new JavdbSearchParser(),
                repo, backupWriter, 90, 10, Clock.systemUTC());

        tool = new RefreshFilmographyTool(resolver);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private ObjectNode args(String slug) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_slug", slug);
        return n;
    }

    @Test
    void refresh_evictsAndRefetches() {
        // Warm L1 first
        resolver.filmographyOf("J9dd");
        assertFalse(resolver.filmographyOf("J9dd").isEmpty());

        var result = (RefreshFilmographyTool.Result) tool.call(args("J9dd"));

        assertEquals("J9dd", result.actressSlug());
        assertEquals(2, result.entryCount());
        assertEquals("ok", result.lastFetchStatus());
    }

    @Test
    void refresh_updatesL2WithNewData() {
        tool.call(args("J9dd"));

        Map<String, String> codeToSlug = repo.getCodeToSlug("J9dd");
        assertEquals("slug1-updated", codeToSlug.get("STAR-334"));
        assertEquals("slug9", codeToSlug.get("STAR-999"));
    }

    @Test
    void missingSlug_throws() {
        ObjectNode badArgs = M.createObjectNode();
        assertThrows(IllegalArgumentException.class, () -> tool.call(badArgs));
    }
}
