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

class AnalyzeTitleVideosToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiVideoRepository videoRepo;
    private AnalyzeTitleVideosTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        videoRepo = new JdbiVideoRepository(jdbi);
        tool = new AnalyzeTitleVideosTool(titleRepo, videoRepo);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void singleVideoReturnsSingleVideoVerdict() {
        long id = titleRepo.save(title("SOLO-001")).getId();
        videoRepo.save(probed(id, "solo.mkv", 3600L, 1920, 1080, "h264", "aac", "mkv"));
        var r = (AnalyzeTitleVideosTool.Result) tool.call(args("SOLO-001"));
        assertEquals("single_video", r.verdict());
    }

    @Test
    void nearIdenticalDurationsFlagAsDuplicates() {
        long id = titleRepo.save(title("DUP-001")).getId();
        videoRepo.save(probed(id, "dup-h264.mkv", 3600L, 1920, 1080, "h264", "aac", "mkv"));
        videoRepo.save(probed(id, "dup-h265.mkv", 3615L, 3840, 2160, "hevc", "aac", "mkv"));
        var r = (AnalyzeTitleVideosTool.Result) tool.call(args("DUP-001"));
        assertEquals("likely_duplicates", r.verdict());
    }

    @Test
    void widelySeparatedDurationsFlagAsSet() {
        long id = titleRepo.save(title("SET-001")).getId();
        videoRepo.save(probed(id, "disc1.mkv", 3600L, 1920, 1080, "h264", "aac", "mkv"));
        videoRepo.save(probed(id, "disc2.mkv", 1800L, 1920, 1080, "h264", "aac", "mkv"));
        var r = (AnalyzeTitleVideosTool.Result) tool.call(args("SET-001"));
        assertEquals("likely_set", r.verdict());
    }

    @Test
    void inBetweenSeparationIsAmbiguous() {
        long id = titleRepo.save(title("AMB-001")).getId();
        videoRepo.save(probed(id, "a.mkv", 3600L, 1920, 1080, "h264", "aac", "mkv"));
        // spread 60s — more than duplicate tolerance (30s), less than set threshold (120s)
        videoRepo.save(probed(id, "b.mkv", 3660L, 1920, 1080, "h264", "aac", "mkv"));
        var r = (AnalyzeTitleVideosTool.Result) tool.call(args("AMB-001"));
        assertEquals("ambiguous", r.verdict());
    }

    @Test
    void missingMetadataBlocksVerdict() {
        long id = titleRepo.save(title("BF-001")).getId();
        videoRepo.save(probed(id, "probed.mkv", 3600L, 1920, 1080, "h264", "aac", "mkv"));
        videoRepo.save(unprobed(id, "unprobed.mkv")); // null metadata
        var r = (AnalyzeTitleVideosTool.Result) tool.call(args("BF-001"));
        assertEquals("insufficient_metadata", r.verdict());
    }

    @Test
    void unknownCodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> tool.call(args("NOPE-001")));
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

    private static Video probed(long titleId, String filename, long durSec, int w, int h,
                                String vc, String ac, String c) {
        return unprobed(titleId, filename).toBuilder()
                .durationSec(durSec).width(w).height(h)
                .videoCodec(vc).audioCodec(ac).container(c)
                .build();
    }

    private static ObjectNode args(String code) {
        ObjectNode n = M.createObjectNode();
        n.put("code", code);
        return n;
    }
}
