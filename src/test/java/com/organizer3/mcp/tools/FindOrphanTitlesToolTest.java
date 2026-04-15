package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import com.organizer3.model.TitleLocation;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FindOrphanTitlesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private JdbiTitleActressRepository titleActressRepo;
    private FindOrphanTitlesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        titleActressRepo = new JdbiTitleActressRepository(jdbi);
        tool = new FindOrphanTitlesTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsLocationlessTitles() throws Exception {
        long aid = actressRepo.save(mk("Aya")).getId();
        Title linked = titleRepo.save(title("LOC-001", aid));
        Title orphan = titleRepo.save(title("LOC-002", aid));
        locationRepo.save(TitleLocation.builder()
                .titleId(linked.getId())
                .volumeId("vol-a")
                .partitionId("part-a")
                .path(Path.of("/some/path"))
                .lastSeenAt(LocalDate.now())
                .build());

        var r = (FindOrphanTitlesTool.Result) tool.call(args(100));
        assertEquals(1, r.locationlessCount());
        assertEquals(orphan.getId(), r.locationless().get(0).titleId());
    }

    @Test
    void flagsActresslessTitles() throws Exception {
        long aid = actressRepo.save(mk("Aya")).getId();
        titleRepo.save(title("HAS-001", aid));                 // has actress_id
        Title junction = titleRepo.save(title("JCT-001", null));
        titleActressRepo.linkAll(junction.getId(), List.of(aid)); // has junction row
        Title noActress = titleRepo.save(title("NONE-001", null)); // neither

        var r = (FindOrphanTitlesTool.Result) tool.call(args(100));
        assertEquals(1, r.actresslessCount());
        assertEquals(noActress.getId(), r.actressless().get(0).titleId());
    }

    @Test
    void emptyDbIsEmpty() throws Exception {
        var r = (FindOrphanTitlesTool.Result) tool.call(args(100));
        assertEquals(0, r.locationlessCount());
        assertEquals(0, r.actresslessCount());
    }

    private static Title title(String code, Long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static ObjectNode args(int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("limit", limit);
        return n;
    }
}
