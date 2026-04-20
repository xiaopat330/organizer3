package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExportAliasesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tempDir;

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private ExportAliasesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        actressRepo = new JdbiActressRepository(jdbi);
        tool = new ExportAliasesTool(actressRepo, tempDir);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void exportsAliasesToSpecifiedPath() {
        long id = actress("Aya Sazanami");
        actressRepo.replaceAllAliases(id, List.of("Haruka Suzumiya", "Aya Konami"));

        Path out = tempDir.resolve("out.yaml");
        var r = (ExportAliasesTool.Result) tool.call(argsWith(out.toString()));

        assertTrue(r.entriesExported() == 1);
        assertEquals(out.toString(), r.path());
        assertTrue(out.toFile().exists());
    }

    @Test
    void defaultsToDataDirFilename() {
        actress("Aya Sazanami");

        var r = (ExportAliasesTool.Result) tool.call(emptyArgs());

        assertEquals(tempDir.resolve("aliases-export.yaml").toString(), r.path());
    }

    @Test
    void exportedYamlCanBeRoundTripped() throws Exception {
        long ayaId = actress("Aya Sazanami");
        long hibikiId = actress("Hibiki Otsuki");
        actressRepo.replaceAllAliases(ayaId, List.of("Haruka Suzumiya", "Aya Konami"));
        actressRepo.replaceAllAliases(hibikiId, List.of("Eri Ando"));

        Path out = tempDir.resolve("roundtrip.yaml");
        tool.call(argsWith(out.toString()));

        // Parse the written file to verify structure
        com.fasterxml.jackson.databind.ObjectMapper yaml =
                new com.fasterxml.jackson.databind.ObjectMapper(
                        new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        com.fasterxml.jackson.databind.JsonNode root = yaml.readTree(out.toFile());
        assertTrue(root.has("alias"));
        assertTrue(root.get("alias").isArray());
        assertEquals(2, root.get("alias").size());
    }

    @Test
    void emptyTableExportsZeroEntries() {
        actress("Aya Sazanami"); // actress with no aliases

        var r = (ExportAliasesTool.Result) tool.call(emptyArgs());

        assertEquals(0, r.entriesExported());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long actress(String name) {
        return actressRepo.save(Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.now())
                .build()).getId();
    }

    private ObjectNode argsWith(String path) {
        ObjectNode n = M.createObjectNode();
        n.put("path", path);
        return n;
    }

    private ObjectNode emptyArgs() {
        return M.createObjectNode();
    }
}
