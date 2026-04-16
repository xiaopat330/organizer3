package com.organizer3.command;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.jdbi.JdbiTitleLocationRepository;
import com.organizer3.repository.jdbi.JdbiTitleRepository;
import com.organizer3.repository.jdbi.JdbiVideoRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.PlainCommandIO;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProbeVideosCommand}. Uses a real {@link SessionContext} and
 * {@link PlainCommandIO} (no Mockito) so the test JVM doesn't load the byte-buddy agent,
 * and a {@link BiFunction} prober so {@link com.organizer3.media.VideoProbe}'s FFmpeg
 * native libraries never load either. Keeps the test worker lean.
 */
class ProbeVideosCommandTest {

    private Connection connection;
    private JdbiVideoRepository videoRepo;
    private JdbiTitleRepository titleRepo;
    private SessionContext session;
    private StringWriter out;
    private PlainCommandIO io;
    private final Map<Long, Map<String, Object>> canned = new HashMap<>();
    private int probeCallCount = 0;

    private final BiFunction<Long, String, Map<String, Object>> fakeProber =
            (id, filename) -> {
                probeCallCount++;
                return canned.getOrDefault(id, Map.of());
            };

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute("INSERT INTO volumes (id, structure_type) VALUES ('a', 'conventional')"));
        videoRepo = new JdbiVideoRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, new JdbiTitleLocationRepository(jdbi));
        session = new SessionContext();
        out = new StringWriter();
        io = new PlainCommandIO(new PrintWriter(out));
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private ProbeVideosCommand command() {
        return new ProbeVideosCommand(videoRepo, fakeProber);
    }

    private void mountVolume(String id) {
        session.setMountedVolume(new VolumeConfig(id, "//host/" + id, "conventional", "host", null));
    }

    private boolean outputContains(String substring) {
        return out.toString().contains(substring);
    }

    @Test
    void refusesWhenNoVolumeMounted() {
        command().execute(new String[]{"probe videos"}, session, io);
        assertTrue(outputContains("No volume mounted"));
        assertEquals(0, probeCallCount);
    }

    @Test
    void refusesWhenRequestedVolumeIsNotTheActiveMount() {
        mountVolume("a");
        command().execute(new String[]{"probe videos", "b"}, session, io);
        assertTrue(outputContains("not the active mount"));
        assertEquals(0, probeCallCount);
    }

    @Test
    void reportsNoWorkWhenAllProbed() {
        mountVolume("a");
        command().execute(new String[]{"probe videos"}, session, io);
        assertTrue(outputContains("No unprobed videos"));
    }

    @Test
    void probesAllUnprobedVideosOnVolumeAndUpdatesRows() {
        mountVolume("a");
        long tid = titleRepo.save(title("SKY-283")).getId();
        Video v1 = videoRepo.save(video(tid, "sky283.mkv"));
        Video v2 = videoRepo.save(video(tid, "sky283-h265.mkv"));

        canned.put(v1.getId(), Map.of("durationSeconds", 3600L, "width", 1920, "height", 1080,
                "videoCodec", "h264", "audioCodec", "aac"));
        canned.put(v2.getId(), Map.of("durationSeconds", 3600L, "width", 3840, "height", 2160,
                "videoCodec", "hevc", "audioCodec", "eac3"));

        command().execute(new String[]{"probe videos"}, session, io);

        Video l1 = videoRepo.findById(v1.getId()).orElseThrow();
        assertEquals(3600L, l1.getDurationSec());
        assertEquals("h264", l1.getVideoCodec());
        assertEquals("mkv",  l1.getContainer());

        Video l2 = videoRepo.findById(v2.getId()).orElseThrow();
        assertEquals("hevc", l2.getVideoCodec());
        assertEquals(3840,   l2.getWidth());
        assertEquals(2, probeCallCount);
    }

    @Test
    void failedProbeLeavesRowNullAndCountsAsFailed() {
        mountVolume("a");
        long tid = titleRepo.save(title("BAD-001")).getId();
        Video v = videoRepo.save(video(tid, "bad.mkv"));
        // canned has no entry → fakeProber returns Map.of() → counted as failure

        command().execute(new String[]{"probe videos"}, session, io);

        Video after = videoRepo.findById(v.getId()).orElseThrow();
        assertNull(after.getDurationSec(), "failed probes leave duration null");
    }

    @Test
    void nullifiesUnknownCodecs() {
        mountVolume("a");
        long tid = titleRepo.save(title("UNK-001")).getId();
        Video v = videoRepo.save(video(tid, "unk.mkv"));
        canned.put(v.getId(), Map.of("durationSeconds", 10L, "width", 1, "height", 1,
                "videoCodec", "unknown", "audioCodec", "unknown"));

        command().execute(new String[]{"probe videos"}, session, io);

        Video after = videoRepo.findById(v.getId()).orElseThrow();
        assertNull(after.getVideoCodec());
        assertNull(after.getAudioCodec());
        assertEquals(10L, after.getDurationSec());
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
}
