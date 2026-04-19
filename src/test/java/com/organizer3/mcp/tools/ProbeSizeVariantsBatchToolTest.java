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
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ProbeSizeVariantsBatchToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiVideoRepository videoRepo;
    private JdbiTitleRepository titleRepo;
    private final Map<Long, Map<String, Object>> canned = new HashMap<>();
    private int probeCalls = 0;
    private final BiFunction<Long, String, Map<String, Object>> prober =
            (id, filename) -> { probeCalls++; return canned.getOrDefault(id, Map.of()); };
    private ProbeSizeVariantsBatchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        videoRepo = new JdbiVideoRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        tool = new ProbeSizeVariantsBatchTool(videoRepo, prober);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void probesSizeVariantCandidatesOnly() {
        // size-variant title: 4x ratio
        long varId = titleRepo.save(title("VAR-001")).getId();
        Video v1 = videoRepo.save(sized(varId, "small.mkv",  500_000_000L));
        Video v2 = videoRepo.save(sized(varId, "large.mkv", 2_000_000_000L));

        // non-variant title: 1.1x ratio — should be skipped
        long normalId = titleRepo.save(title("NRM-001")).getId();
        videoRepo.save(sized(normalId, "p1.mkv", 1_000_000_000L));
        videoRepo.save(sized(normalId, "p2.mkv", 1_100_000_000L));

        canned.put(v1.getId(), goodMeta());
        canned.put(v2.getId(), goodMeta());

        var r = (ProbeSizeVariantsBatchTool.Result) tool.call(args(0, 50, 2.0, 2));
        assertEquals(2, r.scanned(), "only variant title's videos probed");
        assertEquals(2, r.ok());
        assertEquals(0, r.failed());
        assertEquals(2, probeCalls);
    }

    @Test
    void skipsTitlesWithMissingSize() {
        // title where one video lacks size — not a size-variant candidate
        long id = titleRepo.save(title("PARTIAL-001")).getId();
        videoRepo.save(sized(id, "big.mkv",   2_000_000_000L));
        videoRepo.save(unsized(id, "small.mkv"));

        var r = (ProbeSizeVariantsBatchTool.Result) tool.call(args(0, 50, 2.0, 2));
        assertEquals(0, r.scanned());
        assertEquals(0, probeCalls);
    }

    @Test
    void cursorPaginates() {
        long varId = titleRepo.save(title("PAGED-001")).getId();
        Video v1 = videoRepo.save(sized(varId, "a.mkv", 100L));
        Video v2 = videoRepo.save(sized(varId, "b.mkv", 1_000L));
        Video v3 = videoRepo.save(sized(varId, "c.mkv", 10_000L)); // 100x ratio
        for (Video v : new Video[]{v1, v2, v3}) canned.put(v.getId(), goodMeta());

        var r1 = (ProbeSizeVariantsBatchTool.Result) tool.call(args(0, 2, 2.0, 2));
        assertEquals(2, r1.scanned());

        var r2 = (ProbeSizeVariantsBatchTool.Result) tool.call(args(r1.nextCursor(), 2, 2.0, 2));
        assertEquals(1, r2.scanned());
        assertEquals(0, r2.remainingUnprobed());

        var r3 = (ProbeSizeVariantsBatchTool.Result) tool.call(args(r2.nextCursor(), 2, 2.0, 2));
        assertEquals(0, r3.scanned(), "no more unprobed");
    }

    @Test
    void failedProbesAdvanceCursorAndStillCount() {
        long varId = titleRepo.save(title("FAIL-001")).getId();
        Video v1 = videoRepo.save(sized(varId, "a.mkv", 100L));
        Video v2 = videoRepo.save(sized(varId, "b.mkv", 1_000L));
        // no canned entries → both fail

        var r = (ProbeSizeVariantsBatchTool.Result) tool.call(args(0, 50, 2.0, 2));
        assertEquals(2, r.scanned());
        assertEquals(0, r.ok());
        assertEquals(2, r.failed());
        assertEquals(v2.getId(), r.nextCursor());

        // next call past cursor returns empty
        var r2 = (ProbeSizeVariantsBatchTool.Result) tool.call(args(r.nextCursor(), 50, 2.0, 2));
        assertEquals(0, r2.scanned());
    }

    @Test
    void alreadyProbedVideosAreExcluded() {
        long varId = titleRepo.save(title("DONE-001")).getId();
        Video v1 = videoRepo.save(sized(varId, "a.mkv", 100L));
        Video v2 = videoRepo.save(sized(varId, "b.mkv", 1_000L));
        // mark v1 as already probed
        videoRepo.updateMetadata(v1.getId(), 3600L, 1920, 1080, "h264", "aac", "mkv");

        canned.put(v2.getId(), goodMeta());
        var r = (ProbeSizeVariantsBatchTool.Result) tool.call(args(0, 50, 2.0, 2));
        assertEquals(1, r.scanned(), "already-probed v1 excluded");
        assertEquals(1, probeCalls);
    }

    private static Map<String, Object> goodMeta() {
        return Map.of("durationSeconds", 3600L, "width", 1920, "height", 1080,
                "videoCodec", "h264", "audioCodec", "aac");
    }

    private static Title title(String code) {
        return Title.builder().code(code)
                .baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1).build();
    }

    private static Video unsized(long titleId, String filename) {
        return Video.builder()
                .titleId(titleId).volumeId("a")
                .filename(filename).path(Path.of("/" + filename))
                .lastSeenAt(LocalDate.now()).build();
    }

    private static Video sized(long titleId, String filename, long bytes) {
        return unsized(titleId, filename).toBuilder().sizeBytes(bytes).build();
    }

    private static ObjectNode args(long fromId, int limit, double minRatio, int minVideos) {
        ObjectNode n = M.createObjectNode();
        n.put("fromId",     fromId);
        n.put("limit",      limit);
        n.put("min_ratio",  minRatio);
        n.put("min_videos", minVideos);
        return n;
    }
}
