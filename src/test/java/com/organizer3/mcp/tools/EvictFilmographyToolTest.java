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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvictFilmographyToolTest {

    @TempDir Path tempDir;
    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JavdbActressFilmographyRepository repo;
    private JavdbSlugResolver resolver;
    private EvictFilmographyTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiJavdbActressFilmographyRepository(jdbi);

        repo.upsertFilmography("J9dd", new FetchResult(
                "2026-01-01T00:00:00Z", 1, null, "http",
                List.of(new FilmographyEntry("STAR-334", "slug1"))));

        JavdbClient mockClient = mock(JavdbClient.class);
        FilmographyBackupWriter backupWriter = new FilmographyBackupWriter(tempDir);
        resolver = new JavdbSlugResolver(mockClient, new JavdbFilmographyParser(),
                new JavdbSearchParser(), repo, backupWriter, 90, 10, Clock.systemUTC());

        tool = new EvictFilmographyTool(resolver);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private ObjectNode args(String slug) {
        ObjectNode n = M.createObjectNode();
        n.put("actress_slug", slug);
        return n;
    }

    @Test
    void evict_dropsBothL1AndL2() {
        // Warm L1
        resolver.filmographyOf("J9dd");

        var result = (EvictFilmographyTool.Result) tool.call(args("J9dd"));

        assertTrue(result.wasPresent());
        assertTrue(repo.findMeta("J9dd").isEmpty(), "L2 must be cleared");
    }

    @Test
    void evict_unknownActress_wasPresent_false() {
        var result = (EvictFilmographyTool.Result) tool.call(args("nobody"));
        assertFalse(result.wasPresent());
    }

    @Test
    void evict_isIdempotent() {
        tool.call(args("J9dd"));
        assertDoesNotThrow(() -> tool.call(args("J9dd")));
    }
}
