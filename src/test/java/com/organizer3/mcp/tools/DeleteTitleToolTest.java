package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DeleteTitleToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private Jdbi jdbi;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository titleLocationRepo;
    private JdbiActressRepository actressRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private JdbiVideoRepository videoRepo;
    private DeleteTitleTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        titleLocationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, titleLocationRepo);
        actressRepo = new JdbiActressRepository(jdbi);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        videoRepo = new JdbiVideoRepository(jdbi);
        tool = new DeleteTitleTool(jdbi, titleRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void dryRunReturnsCountsWithoutMutating() {
        long titleId = givenTitleWithEverything();

        var r = (DeleteTitleTool.Result) tool.call(args(titleId, true));
        assertTrue(r.dryRun());
        assertNotNull(titleRepo.findById(titleId).orElse(null), "title should still exist after dry-run");
        assertTrue(r.plan().summary().contains("XYZ-001"));

        // Changes contain non-zero counts for each dependent table
        int totalRows = r.plan().changes().stream().mapToInt(DeleteTitleTool.Change::rows).sum();
        assertTrue(totalRows >= 4, "plan should account for title + deps, got " + totalRows);
    }

    @Test
    void executeRemovesTitleAndAllDependents() {
        long titleId = givenTitleWithEverything();

        var r = (DeleteTitleTool.Result) tool.call(args(titleId, false));
        assertFalse(r.dryRun());

        assertTrue(titleRepo.findById(titleId).isEmpty(), "title row should be deleted");
        assertEquals(0, titleLocationRepo.findByTitle(titleId).size(), "locations cascade gone");
        assertEquals(0, videoRepo.findByTitle(titleId).size(), "videos cascade gone");
        assertEquals(0, titleActressRepo.findActressIdsByTitle(titleId).size(), "credits cascade gone");
    }

    @Test
    void rejectsMissingTitle() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(9999L, true)));
    }

    @Test
    void defaultsToDryRun() {
        long titleId = givenTitleWithEverything();
        ObjectNode a = M.createObjectNode();
        a.put("id", titleId);
        // no dryRun key

        var r = (DeleteTitleTool.Result) tool.call(a);
        assertTrue(r.dryRun());
        assertTrue(titleRepo.findById(titleId).isPresent());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Creates a title with: 1 actress, 1 credit row, 1 location, 1 video. Returns title id. */
    private long givenTitleWithEverything() {
        long actressId = actressRepo.save(Actress.builder()
                .canonicalName("Test Actress")
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build()).getId();
        Title t = titleRepo.save(Title.builder()
                .code("XYZ-001")
                .baseCode("XYZ-00001")
                .label("XYZ")
                .seqNum(1)
                .actressId(actressId)
                .build());
        titleLocationRepo.save(TitleLocation.builder()
                .titleId(t.getId())
                .volumeId("a")
                .partitionId("library")
                .path(java.nio.file.Path.of("/stars/library/Test Actress/Test Actress (XYZ-001)"))
                .addedDate(LocalDate.of(2024, 1, 2))
                .lastSeenAt(LocalDate.of(2024, 1, 2))
                .build());
        videoRepo.save(Video.builder()
                .titleId(t.getId())
                .volumeId("a")
                .filename("xyz001.mkv")
                .path(java.nio.file.Path.of("/stars/library/Test Actress/Test Actress (XYZ-001)/video"))
                .lastSeenAt(LocalDate.of(2024, 1, 2))
                .build());
        titleActressRepo.linkAll(t.getId(), java.util.List.of(actressId));
        return t.getId();
    }

    private ObjectNode args(long id, boolean dryRun) {
        ObjectNode n = M.createObjectNode();
        n.put("id", id);
        n.put("dryRun", dryRun);
        return n;
    }
}
