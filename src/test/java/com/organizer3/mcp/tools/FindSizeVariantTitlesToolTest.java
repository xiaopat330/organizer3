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

class FindSizeVariantTitlesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiVideoRepository videoRepo;
    private FindSizeVariantTitlesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        videoRepo = new JdbiVideoRepository(jdbi);
        tool = new FindSizeVariantTitlesTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void flagsTitlesWhereSizeRatioExceedsThreshold() {
        long id = titleRepo.save(title("SV-001")).getId();
        videoRepo.save(sized(id, "hd.mkv",   500_000_000L));
        videoRepo.save(sized(id, "h265.mkv", 2_000_000_000L)); // 4x ratio

        var r = (FindSizeVariantTitlesTool.Result) tool.call(args(2.0, 2, 100));
        assertEquals(1, r.count());
        var row = r.candidates().get(0);
        assertEquals("SV-001", row.code());
        assertEquals(4.0, row.sizeRatio(), 0.01);
    }

    @Test
    void skipsTitlesBelowRatioThreshold() {
        long id = titleRepo.save(title("SET-001")).getId();
        // Two roughly equal sizes — likely a multi-part set, not a duplicate.
        videoRepo.save(sized(id, "disc1.mkv", 1_000_000_000L));
        videoRepo.save(sized(id, "disc2.mkv", 1_100_000_000L)); // 1.1x

        var r = (FindSizeVariantTitlesTool.Result) tool.call(args(2.0, 2, 100));
        assertEquals(0, r.count());
    }

    @Test
    void skipsTitlesWithAnyMissingSize() {
        long id = titleRepo.save(title("PARTIAL-001")).getId();
        videoRepo.save(sized(id, "a.mkv", 500_000_000L));
        videoRepo.save(unsized(id, "b.mkv"));

        var r = (FindSizeVariantTitlesTool.Result) tool.call(args(1.5, 2, 100));
        assertEquals(0, r.count(), "any null size on a title disqualifies it");
    }

    @Test
    void respectsMinVideosFilter() {
        long id = titleRepo.save(title("TWO-001")).getId();
        videoRepo.save(sized(id, "a.mkv", 100L));
        videoRepo.save(sized(id, "b.mkv", 1000L));
        assertEquals(1, ((FindSizeVariantTitlesTool.Result) tool.call(args(2.0, 2, 100))).count());
        assertEquals(0, ((FindSizeVariantTitlesTool.Result) tool.call(args(2.0, 3, 100))).count());
    }

    @Test
    void ordersByRatioDescending() {
        long a = titleRepo.save(title("LOW-001")).getId();
        videoRepo.save(sized(a, "a.mkv", 1_000L));
        videoRepo.save(sized(a, "b.mkv", 3_000L)); // 3x

        long b = titleRepo.save(title("HIGH-001")).getId();
        videoRepo.save(sized(b, "a.mkv", 1_000L));
        videoRepo.save(sized(b, "b.mkv", 10_000L)); // 10x

        var r = (FindSizeVariantTitlesTool.Result) tool.call(args(2.0, 2, 100));
        assertEquals(2, r.count());
        assertEquals("HIGH-001", r.candidates().get(0).code());
        assertEquals("LOW-001",  r.candidates().get(1).code());
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

    private static ObjectNode args(double minRatio, int minVideos, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("min_ratio",  minRatio);
        n.put("min_videos", minVideos);
        n.put("limit",      limit);
        return n;
    }
}
