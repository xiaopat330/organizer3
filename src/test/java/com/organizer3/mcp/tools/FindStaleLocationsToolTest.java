package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
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

class FindStaleLocationsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private Jdbi jdbi;
    private FindStaleLocationsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        tool = new FindStaleLocationsTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsLocationsOlderThanVolumeLastSynced() throws Exception {
        // Volume synced on 2024-06-01
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-a', 'conventional', '2024-06-01')"));

        long tid1 = titleRepo.save(title("STALE-001")).getId();
        long tid2 = titleRepo.save(title("FRESH-001")).getId();
        // Stale — last seen before sync
        locationRepo.save(TitleLocation.builder()
                .titleId(tid1).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/stale")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());
        // Fresh — last seen at sync time
        locationRepo.save(TitleLocation.builder()
                .titleId(tid2).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/fresh")).lastSeenAt(LocalDate.of(2024, 6, 1)).build());

        var r = (FindStaleLocationsTool.Result) tool.call(args(null, 100));
        assertEquals(1, r.count());
        assertEquals("STALE-001", r.staleLocations().get(0).titleCode());
    }

    @Test
    void doesNotFlagLocationsSeenOnSameDayAsSync() throws Exception {
        // last_synced_at is stored as a datetime (e.g. '2024-06-01T10:14:37');
        // last_seen_at is stored as a date (e.g. '2024-06-01').
        // String comparison '2024-06-01' < '2024-06-01T10:14:37' is TRUE in SQLite
        // because the date is a prefix of the datetime, making the shorter string
        // lexicographically lesser. DATE() wrapping prevents this false positive.
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-c', 'conventional', '2024-06-01T10:14:37')"));

        long tid = titleRepo.save(title("SAME-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-c").partitionId("p1")
                .path(Path.of("/same")).lastSeenAt(LocalDate.of(2024, 6, 1)).build());

        var r = (FindStaleLocationsTool.Result) tool.call(args(null, 100));
        assertEquals(0, r.count(), "Location seen on sync day must not be flagged as stale");
    }

    @Test
    void skipsVolumesWithNullLastSynced() throws Exception {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')"));
        long tid = titleRepo.save(title("X-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tid).volumeId("vol-b").partitionId("p1")
                .path(Path.of("/x")).lastSeenAt(LocalDate.of(2020, 1, 1)).build());

        var r = (FindStaleLocationsTool.Result) tool.call(args(null, 100));
        assertEquals(0, r.count());
    }

    @Test
    void volumeIdFilterRestrictsResults() throws Exception {
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-a', 'conventional', '2024-06-01')");
            h.execute("INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol-b', 'conventional', '2024-06-01')");
        });
        long tidA = titleRepo.save(title("A-001")).getId();
        long tidB = titleRepo.save(title("B-001")).getId();
        locationRepo.save(TitleLocation.builder()
                .titleId(tidA).volumeId("vol-a").partitionId("p1")
                .path(Path.of("/a")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());
        locationRepo.save(TitleLocation.builder()
                .titleId(tidB).volumeId("vol-b").partitionId("p1")
                .path(Path.of("/b")).lastSeenAt(LocalDate.of(2024, 5, 1)).build());

        var r = (FindStaleLocationsTool.Result) tool.call(args("vol-a", 100));
        assertEquals(1, r.count());
        assertEquals("vol-a", r.staleLocations().get(0).volumeId());
    }

    private static Title title(String code) {
        return Title.builder()
                .code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0])
                .seqNum(1)
                .build();
    }

    private static ObjectNode args(String volumeId, int limit) {
        ObjectNode n = M.createObjectNode();
        if (volumeId != null) n.put("volume_id", volumeId);
        n.put("limit", limit);
        return n;
    }
}
