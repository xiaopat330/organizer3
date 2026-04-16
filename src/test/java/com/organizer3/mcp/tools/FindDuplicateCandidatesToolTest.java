package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FindDuplicateCandidatesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiVideoRepository videoRepo;
    private FindDuplicateCandidatesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        videoRepo = new JdbiVideoRepository(jdbi);
        tool = new FindDuplicateCandidatesTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsTitleWithNearIdenticalDurations() {
        long id = titleRepo.save(title("DUP-001")).getId();
        videoRepo.save(probed(id, "a.mkv", 3600L, "h264", 1920, 1080));
        videoRepo.save(probed(id, "b.mkv", 3610L, "hevc", 3840, 2160));
        var r = (FindDuplicateCandidatesTool.Result) tool.call(args(30, 100));
        assertEquals(1, r.count());
        assertEquals("DUP-001", r.candidates().get(0).code());
    }

    @Test
    void skipsLegitimateSets() {
        long id = titleRepo.save(title("SET-001")).getId();
        videoRepo.save(probed(id, "disc1.mkv", 3600L, "h264", 1920, 1080));
        videoRepo.save(probed(id, "disc2.mkv", 1800L, "h264", 1920, 1080));
        var r = (FindDuplicateCandidatesTool.Result) tool.call(args(30, 100));
        assertEquals(0, r.count());
    }

    @Test
    void skipsTitlesWithAnyUnprobedVideo() {
        long id = titleRepo.save(title("BF-001")).getId();
        videoRepo.save(probed(id, "a.mkv", 3600L, "h264", 1920, 1080));
        videoRepo.save(unprobed(id, "b.mkv"));
        var r = (FindDuplicateCandidatesTool.Result) tool.call(args(30, 100));
        assertEquals(0, r.count(), "partially-probed titles are silent, not false positives");
    }

    @Test
    void respectsCustomTolerance() {
        long id = titleRepo.save(title("T-001")).getId();
        // 50s spread — within 60s tolerance but outside default 30s
        videoRepo.save(probed(id, "a.mkv", 3600L, "h264", 1920, 1080));
        videoRepo.save(probed(id, "b.mkv", 3650L, "hevc", 3840, 2160));
        assertEquals(0, ((FindDuplicateCandidatesTool.Result) tool.call(args(30, 100))).count());
        assertEquals(1, ((FindDuplicateCandidatesTool.Result) tool.call(args(60, 100))).count());
    }

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static Video unprobed(long titleId, String filename) {
        return Video.builder()
                .titleId(titleId).volumeId("a")
                .filename(filename).path(Path.of("/" + filename))
                .lastSeenAt(LocalDate.now()).build();
    }

    private static Video probed(long titleId, String filename, long durSec,
                                String codec, int w, int h) {
        return unprobed(titleId, filename).toBuilder()
                .durationSec(durSec).width(w).height(h)
                .videoCodec(codec).audioCodec("aac").container("mkv")
                .build();
    }

    private static ObjectNode args(int tolerance, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("duration_tolerance_sec", tolerance);
        n.put("limit", limit);
        return n;
    }
}
