package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiActressRepository;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ListActressesWithMisnamedFoldersToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiActressRepository actressRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private ListActressesWithMisnamedFoldersTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')");
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')");
        });
        actressRepo = new JdbiActressRepository(jdbi);
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        tool = new ListActressesWithMisnamedFoldersTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsActressWithFolderPathNotMatchingCanonical() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        long tid = titleRepo.save(title("SKY-283", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/archive/Aino Nami (SKY-283)"));

        var r = (ListActressesWithMisnamedFoldersTool.Result) tool.call(args(null, 100));
        assertEquals(1, r.count());
        assertEquals("Nami Aino", r.actresses().get(0).canonicalName());
        assertEquals(1, r.actresses().get(0).mismatchedLocations());
    }

    @Test
    void ignoresMatchingPaths() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        long tid = titleRepo.save(title("SKY-283", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/stars/library/Nami Aino/SKY-283"));

        var r = (ListActressesWithMisnamedFoldersTool.Result) tool.call(args(null, 100));
        assertEquals(0, r.count());
    }

    @Test
    void caseInsensitive() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        long tid = titleRepo.save(title("SKY-283", aid)).getId();
        locationRepo.save(loc(tid, "vol-a", "/archive/NAMI AINO (SKY-283)"));

        var r = (ListActressesWithMisnamedFoldersTool.Result) tool.call(args(null, 100));
        assertEquals(0, r.count(), "case-insensitive match — lowercase path contains the name");
    }

    @Test
    void ranksByMismatchCountDescending() throws Exception {
        long a1 = actressRepo.save(mk("One Mismatch")).getId();
        long a3 = actressRepo.save(mk("Three Mismatches")).getId();
        long t1 = titleRepo.save(title("T1-001", a1)).getId();
        locationRepo.save(loc(t1, "vol-a", "/bad1"));
        for (int i = 0; i < 3; i++) {
            long tid = titleRepo.save(title("T3-" + (100 + i), a3)).getId();
            locationRepo.save(loc(tid, "vol-a", "/bad-" + i));
        }

        var r = (ListActressesWithMisnamedFoldersTool.Result) tool.call(args(null, 100));
        assertEquals(2, r.count());
        assertEquals("Three Mismatches", r.actresses().get(0).canonicalName());
        assertEquals(3, r.actresses().get(0).mismatchedLocations());
    }

    @Test
    void volumeFilterRestrictsScope() throws Exception {
        long aid = actressRepo.save(mk("Nami Aino")).getId();
        long t1 = titleRepo.save(title("A-001", aid)).getId();
        long t2 = titleRepo.save(title("B-001", aid)).getId();
        locationRepo.save(loc(t1, "vol-a", "/bad-on-a"));
        locationRepo.save(loc(t2, "vol-b", "/bad-on-b"));

        var allR = (ListActressesWithMisnamedFoldersTool.Result) tool.call(args(null, 100));
        assertEquals(2, allR.actresses().get(0).mismatchedLocations());

        var aOnly = (ListActressesWithMisnamedFoldersTool.Result) tool.call(args("vol-a", 100));
        assertEquals(1, aOnly.actresses().get(0).mismatchedLocations());
    }

    private static Actress mk(String name) {
        return Actress.builder()
                .canonicalName(name)
                .tier(Actress.Tier.LIBRARY)
                .firstSeenAt(LocalDate.of(2024, 1, 1))
                .build();
    }

    private static Title title(String code, long actressId) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .actressId(actressId)
                .build();
    }

    private static TitleLocation loc(long titleId, String volumeId, String path) {
        return TitleLocation.builder()
                .titleId(titleId).volumeId(volumeId).partitionId("p")
                .path(Path.of(path)).lastSeenAt(LocalDate.now()).build();
    }

    private static ObjectNode args(String volumeId, int limit) {
        ObjectNode n = M.createObjectNode();
        if (volumeId != null) n.put("volume_id", volumeId);
        n.put("limit", limit);
        return n;
    }
}
