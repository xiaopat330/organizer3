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

class ListMultiVideoTitlesToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Connection connection;
    private JdbiTitleRepository titleRepo;
    private JdbiVideoRepository videoRepo;
    private ListMultiVideoTitlesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        videoRepo = new JdbiVideoRepository(jdbi);
        tool = new ListMultiVideoTitlesTool(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void listsOnlyTitlesAtOrAboveThreshold() {
        long oneVideo = titleRepo.save(title("ABP-001")).getId();
        videoRepo.save(video(oneVideo, "abp001.mkv"));

        long twoVideos = titleRepo.save(title("ABP-002")).getId();
        videoRepo.save(video(twoVideos, "abp002-h264.mkv"));
        videoRepo.save(video(twoVideos, "abp002-h265.mkv"));

        var r = (ListMultiVideoTitlesTool.Result) tool.call(args(2, 100));
        assertEquals(1, r.count());
        assertEquals("ABP-002", r.titles().get(0).code());
        assertEquals(2, r.titles().get(0).videoCount());
    }

    @Test
    void sortsByVideoCountDescending() {
        long a = titleRepo.save(title("A-001")).getId();
        videoRepo.save(video(a, "a1.mkv"));
        videoRepo.save(video(a, "a2.mkv"));

        long b = titleRepo.save(title("B-001")).getId();
        for (int i = 0; i < 4; i++) videoRepo.save(video(b, "b" + i + ".mkv"));

        var r = (ListMultiVideoTitlesTool.Result) tool.call(args(2, 100));
        assertEquals(2, r.count());
        assertEquals("B-001", r.titles().get(0).code());
        assertEquals(4, r.titles().get(0).videoCount());
    }

    @Test
    void returnsFilenamesInOrder() {
        long id = titleRepo.save(title("X-001")).getId();
        videoRepo.save(video(id, "a.mkv"));
        videoRepo.save(video(id, "b.mkv"));

        var r = (ListMultiVideoTitlesTool.Result) tool.call(args(2, 100));
        assertEquals(2, r.titles().get(0).filenames().size());
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

    private static ObjectNode args(int minVideos, int limit) {
        ObjectNode n = M.createObjectNode();
        n.put("min_videos", minVideos);
        n.put("limit", limit);
        return n;
    }
}
