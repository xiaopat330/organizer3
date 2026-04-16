package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.shell.SessionContext;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ProbeVideosBatchToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiVideoRepository videoRepo;
    private JdbiTitleRepository titleRepo;
    private SessionContext session;
    private final Map<Long, Map<String, Object>> canned = new HashMap<>();
    private int probeCalls = 0;
    private final BiFunction<Long, String, Map<String, Object>> prober =
            (id, filename) -> { probeCalls++; return canned.getOrDefault(id, Map.of()); };
    private ProbeVideosBatchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        videoRepo = new JdbiVideoRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        session = new SessionContext();
        session.setMountedVolume(new VolumeConfig("a", "//host/a", "conventional", "host", null));
        tool = new ProbeVideosBatchTool(session, videoRepo, prober);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void rejectsWhenNoVolumeMounted() {
        session.setMountedVolume(null);
        assertThrows(IllegalArgumentException.class, () -> tool.call(args(0, 50)));
    }

    @Test
    void probesBatchAndReportsProgress() {
        long tid = titleRepo.save(title("SKY-001")).getId();
        Video v1 = videoRepo.save(video(tid, "a.mkv"));
        Video v2 = videoRepo.save(video(tid, "b.mkv"));
        canned.put(v1.getId(), Map.of("durationSeconds", 3600L, "width", 1920, "height", 1080,
                "videoCodec", "h264", "audioCodec", "aac"));
        canned.put(v2.getId(), Map.of("durationSeconds", 1800L, "width", 1280, "height", 720,
                "videoCodec", "hevc", "audioCodec", "aac"));

        ProbeVideosBatchTool.Result r = (ProbeVideosBatchTool.Result) tool.call(args(0, 50));
        assertEquals(2, r.scanned());
        assertEquals(2, r.ok());
        assertEquals(0, r.failed());
        assertEquals(0, r.remainingUnprobed());
        assertEquals(v2.getId(), r.nextCursor(), "cursor advances to max id in batch");

        Video reloaded = videoRepo.findById(v1.getId()).orElseThrow();
        assertEquals(3600L, reloaded.getDurationSec());
        assertEquals("h264", reloaded.getVideoCodec());
        assertEquals("mkv",  reloaded.getContainer());
    }

    @Test
    void cursorPaginatesAcrossCalls() {
        long tid = titleRepo.save(title("PAGE-001")).getId();
        Video v1 = videoRepo.save(video(tid, "a.mkv"));
        Video v2 = videoRepo.save(video(tid, "b.mkv"));
        Video v3 = videoRepo.save(video(tid, "c.mkv"));
        for (Video v : new Video[]{v1, v2, v3}) {
            canned.put(v.getId(), Map.of("durationSeconds", 100L, "width", 1, "height", 1,
                    "videoCodec", "h264", "audioCodec", "aac"));
        }

        ProbeVideosBatchTool.Result r1 = (ProbeVideosBatchTool.Result) tool.call(args(0, 2));
        assertEquals(2, r1.scanned());
        ProbeVideosBatchTool.Result r2 = (ProbeVideosBatchTool.Result) tool.call(args(r1.nextCursor(), 2));
        assertEquals(1, r2.scanned());
        assertEquals(0, r2.remainingUnprobed());

        ProbeVideosBatchTool.Result r3 = (ProbeVideosBatchTool.Result) tool.call(args(r2.nextCursor(), 2));
        assertEquals(0, r3.scanned(), "no more unprobed rows");
    }

    @Test
    void failedProbesAdvanceCursorSoLoopTerminates() {
        long tid = titleRepo.save(title("BAD-001")).getId();
        Video v1 = videoRepo.save(video(tid, "a.mkv"));
        Video v2 = videoRepo.save(video(tid, "b.mkv"));
        // no canned entries → both "fail" (empty map)

        ProbeVideosBatchTool.Result r1 = (ProbeVideosBatchTool.Result) tool.call(args(0, 50));
        assertEquals(2, r1.scanned());
        assertEquals(0, r1.ok());
        assertEquals(2, r1.failed());
        assertEquals(v2.getId(), r1.nextCursor());

        // Calling again with the returned cursor must return empty — not re-hit the failed rows
        ProbeVideosBatchTool.Result r2 = (ProbeVideosBatchTool.Result) tool.call(args(r1.nextCursor(), 50));
        assertEquals(0, r2.scanned());
        // The failed rows are still unprobed at the DB level (duration_sec IS NULL) but the
        // cursor has moved past them — exactly what prevents the infinite loop.
        assertEquals(2, r2.remainingUnprobed());
    }

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static Video video(long titleId, String filename) {
        return Video.builder()
                .titleId(titleId).volumeId("a")
                .filename(filename).path(Path.of("/" + filename))
                .lastSeenAt(LocalDate.now()).build();
    }

    private static ObjectNode args(long fromId, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("fromId", fromId);
        n.put("limit", limit);
        return n;
    }
}
