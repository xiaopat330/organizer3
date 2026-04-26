package com.organizer3.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiVideoRepository using an in-memory SQLite database.
 */
class JdbiVideoRepositoryTest {

    private JdbiVideoRepository videoRepo;
    private JdbiTitleRepository titleRepo;
    private JdbiTitleLocationRepository locationRepo;
    private Jdbi jdbi;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        locationRepo = new JdbiTitleLocationRepository(jdbi);
        titleRepo = new JdbiTitleRepository(jdbi, locationRepo);
        videoRepo = new JdbiVideoRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- save / findById ---

    @Test
    void saveNewVideoAssignsId() {
        Title title = saveTitle("ABP-001");
        Video saved = videoRepo.save(video(title.getId(), "ABP-001.mp4", "/queue/ABP-001/ABP-001.mp4"));

        assertNotNull(saved.getId());
        assertTrue(saved.getId() > 0);
    }

    @Test
    void findByIdReturnsVideo() {
        Title title = saveTitle("ABP-001");
        Video saved = videoRepo.save(video(title.getId(), "ABP-001.mp4", "/queue/ABP-001/ABP-001.mp4"));

        Optional<Video> found = videoRepo.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("ABP-001.mp4", found.get().getFilename());
        assertEquals(Path.of("/queue/ABP-001/ABP-001.mp4"), found.get().getPath());
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        assertTrue(videoRepo.findById(999L).isEmpty());
    }

    // --- save (update) ---

    @Test
    void saveUpdatesExistingVideo() {
        Title title = saveTitle("ABP-001");
        Video saved = videoRepo.save(video(title.getId(), "ABP-001.mp4", "/old/path.mp4"));

        Video updated = videoRepo.save(Video.builder().id(saved.getId()).titleId(title.getId())
                .volumeId("vol-a")
                .filename("ABP-001-hd.mp4").path(Path.of("/new/path.mp4")).lastSeenAt(LocalDate.of(2025, 6, 1)).build());

        Optional<Video> found = videoRepo.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("ABP-001-hd.mp4", found.get().getFilename());
        assertEquals(Path.of("/new/path.mp4"), found.get().getPath());
    }

    // --- findByTitle ---

    @Test
    void findByTitleReturnsVideosOrderedByFilename() {
        Title title = saveTitle("ABP-001");
        videoRepo.save(video(title.getId(), "part2.mp4", "/p2.mp4"));
        videoRepo.save(video(title.getId(), "part1.mp4", "/p1.mp4"));

        List<Video> videos = videoRepo.findByTitle(title.getId());
        assertEquals(2, videos.size());
        assertEquals("part1.mp4", videos.get(0).getFilename());
        assertEquals("part2.mp4", videos.get(1).getFilename());
    }

    @Test
    void findByTitleReturnsEmptyForNoVideos() {
        Title title = saveTitle("ABP-001");
        assertTrue(videoRepo.findByTitle(title.getId()).isEmpty());
    }

    // --- delete ---

    @Test
    void deleteRemovesVideo() {
        Title title = saveTitle("ABP-001");
        Video saved = videoRepo.save(video(title.getId(), "ABP-001.mp4", "/p.mp4"));

        videoRepo.delete(saved.getId());

        assertTrue(videoRepo.findById(saved.getId()).isEmpty());
    }

    // --- deleteByTitle ---

    @Test
    void deleteByTitleRemovesAllVideosForTitle() {
        Title t1 = saveTitle("ABP-001");
        Title t2 = saveTitle("ABP-002");
        videoRepo.save(video(t1.getId(), "a.mp4", "/a.mp4"));
        videoRepo.save(video(t1.getId(), "b.mp4", "/b.mp4"));
        videoRepo.save(video(t2.getId(), "c.mp4", "/c.mp4"));

        videoRepo.deleteByTitle(t1.getId());

        assertTrue(videoRepo.findByTitle(t1.getId()).isEmpty());
        assertEquals(1, videoRepo.findByTitle(t2.getId()).size());
    }

    // --- deleteByVolume ---

    @Test
    void deleteByVolumeRemovesAllVideosForVolume() {
        Title t1 = saveTitle("ABP-001");
        videoRepo.save(video(t1.getId(), "a.mp4", "/a.mp4"));
        videoRepo.save(video(t1.getId(), "b.mp4", "/b.mp4"));

        videoRepo.deleteByVolume("vol-a");

        assertTrue(videoRepo.findByTitle(t1.getId()).isEmpty());
    }

    /**
     * Rule 3 false-positive guard: deleting videos for vol-a must not touch vol-b. A bad
     * predicate or mis-bound volumeId that dropped the WHERE clause would wipe videos
     * across the whole catalog.
     */
    @Test
    void deleteByVolumeDoesNotTouchOtherVolumes() {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-b', 'conventional')"));
        Title shared = saveTitle("ABP-001");
        videoRepo.save(video(shared.getId(), "a.mp4", "/a.mp4"));
        videoRepo.save(Video.builder().titleId(shared.getId()).volumeId("vol-b")
                .filename("b.mp4").path(Path.of("/b.mp4")).lastSeenAt(LocalDate.now()).build());

        videoRepo.deleteByVolume("vol-a");

        List<Video> survivors = videoRepo.findByTitle(shared.getId());
        assertEquals(1, survivors.size(), "vol-b video must survive delete of vol-a");
        assertEquals("vol-b", survivors.get(0).getVolumeId());
    }

    // --- deleteByVolumeAndPartition ---

    @Test
    void deleteByVolumeAndPartitionRemovesOnlyMatchingPartition() {
        Title queueTitle = titleRepo.save(Title.builder()
                .code("ABP-001").baseCode("ABP-00001").label("ABP").seqNum(1)
                .build());
        locationRepo.save(TitleLocation.builder()
                .titleId(queueTitle.getId())
                .volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/ABP-001"))
                .lastSeenAt(LocalDate.now())
                .build());

        Title starsTitle = titleRepo.save(Title.builder()
                .code("ABP-002").baseCode("ABP-00002").label("ABP").seqNum(2)
                .build());
        locationRepo.save(TitleLocation.builder()
                .titleId(starsTitle.getId())
                .volumeId("vol-a").partitionId("stars")
                .path(Path.of("/stars/ABP-002"))
                .lastSeenAt(LocalDate.now())
                .build());

        videoRepo.save(video(queueTitle.getId(), "q.mp4", "/q.mp4"));
        videoRepo.save(video(starsTitle.getId(), "s.mp4", "/s.mp4"));

        videoRepo.deleteByVolumeAndPartition("vol-a", "queue");

        assertTrue(videoRepo.findByTitle(queueTitle.getId()).isEmpty());
        assertEquals(1, videoRepo.findByTitle(starsTitle.getId()).size());
    }

    // --- v18 metadata ---

    @org.junit.jupiter.api.Test
    void saveRoundtripsMetadataFields() {
        Title t = saveTitle("SKY-283");
        Video saved = videoRepo.save(Video.builder()
                .titleId(t.getId()).volumeId("vol-a")
                .filename("sky283.mkv").path(Path.of("/x/sky283.mkv"))
                .lastSeenAt(LocalDate.now())
                .durationSec(3600L).width(1920).height(1080)
                .videoCodec("h264").audioCodec("aac").container("mkv")
                .build());

        Video loaded = videoRepo.findById(saved.getId()).orElseThrow();
        assertEquals(3600L,  loaded.getDurationSec());
        assertEquals(1920,   loaded.getWidth());
        assertEquals(1080,   loaded.getHeight());
        assertEquals("h264", loaded.getVideoCodec());
        assertEquals("aac",  loaded.getAudioCodec());
        assertEquals("mkv",  loaded.getContainer());
    }

    @org.junit.jupiter.api.Test
    void updateMetadataOverwritesOnlyMetadataFields() {
        Title t = saveTitle("SKY-283");
        Video v = videoRepo.save(video(t.getId(), "x.mkv", "/x.mkv"));
        assertNull(v.getDurationSec(), "fresh row has null metadata");

        videoRepo.updateMetadata(v.getId(), 7200L, 3840, 2160, "hevc", "eac3", "mkv");
        Video after = videoRepo.findById(v.getId()).orElseThrow();
        assertEquals(7200L, after.getDurationSec());
        assertEquals("hevc", after.getVideoCodec());
        assertEquals("x.mkv", after.getFilename(), "non-metadata fields untouched");
    }

    // --- v19 size_bytes ---

    @org.junit.jupiter.api.Test
    void saveRoundtripsSizeBytes() {
        Title t = saveTitle("ABP-001");
        Video saved = videoRepo.save(Video.builder()
                .titleId(t.getId()).volumeId("vol-a")
                .filename("abp001.mkv").path(Path.of("/x/abp001.mkv"))
                .lastSeenAt(LocalDate.now())
                .sizeBytes(2_147_483_648L)
                .build());

        Video loaded = videoRepo.findById(saved.getId()).orElseThrow();
        assertEquals(2_147_483_648L, loaded.getSizeBytes());
    }

    @org.junit.jupiter.api.Test
    void saveWithNullSizeBytesPreservesExistingValue() {
        Title t = saveTitle("ABP-001");
        Video saved = videoRepo.save(Video.builder()
                .titleId(t.getId()).volumeId("vol-a")
                .filename("abp001.mkv").path(Path.of("/x/abp001.mkv"))
                .lastSeenAt(LocalDate.now())
                .sizeBytes(123L)
                .build());

        // Update path with a null sizeBytes — existing value must not be clobbered.
        videoRepo.save(saved.toBuilder().sizeBytes(null).build());

        assertEquals(123L, videoRepo.findById(saved.getId()).orElseThrow().getSizeBytes());
    }

    @org.junit.jupiter.api.Test
    void findUnprobedAndCountUnprobedRespectVolumeFilter() {
        Title t = saveTitle("ABP-001");
        videoRepo.save(video(t.getId(), "a.mkv", "/a"));
        videoRepo.save(video(t.getId(), "b.mkv", "/b"));
        Video probed = videoRepo.save(video(t.getId(), "c.mkv", "/c"));
        videoRepo.updateMetadata(probed.getId(), 100L, 1280, 720, "h264", "aac", "mkv");

        assertEquals(2, videoRepo.countUnprobed(null));
        assertEquals(2, videoRepo.countUnprobed("vol-a"));
        assertEquals(0, videoRepo.countUnprobed("vol-nowhere"));
        assertEquals(2, videoRepo.findUnprobed(null, 0L, 10).size());
        assertEquals(1, videoRepo.findUnprobed(null, 0L, 1).size(), "limit respected");
    }

    // --- Helpers ---

    /** Save a title with a location in the "queue" partition on vol-a. */
    private Title saveTitle(String code) {
        Title saved = titleRepo.save(Title.builder()
                .code(code).baseCode(code.toUpperCase().replace("-", "-00"))
                .label(code.split("-")[0]).seqNum(1)
                .build());
        locationRepo.save(TitleLocation.builder()
                .titleId(saved.getId())
                .volumeId("vol-a").partitionId("queue")
                .path(Path.of("/queue/" + code))
                .lastSeenAt(LocalDate.now())
                .build());
        return saved;
    }

    private Video video(long titleId, String filename, String path) {
        return Video.builder()
                .titleId(titleId)
                .volumeId("vol-a")
                .filename(filename)
                .path(Path.of(path))
                .lastSeenAt(LocalDate.now())
                .build();
    }
}
