package com.organizer3.avstars.repository.jdbi;

import com.organizer3.avstars.model.AvVideo;
import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdbiAvVideoRepository using an in-memory SQLite database.
 */
class JdbiAvVideoRepositoryTest {

    private JdbiAvVideoRepository repo;
    private long actressId;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Jdbi jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('qnap_av', 'avstars')");
            actressId = h.createUpdate("""
                    INSERT INTO av_actresses (volume_id, folder_name, stage_name, first_seen_at,
                        video_count, total_size_bytes)
                    VALUES ('qnap_av', 'Anissa Kate', 'Anissa Kate', '2024-01-01T00:00:00', 0, 0)
                    """).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
        });
        repo = new JdbiAvVideoRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    // --- upsert / findById ---

    @Test
    void upsertNewVideoReturnsId() {
        long id = repo.upsert(video("video1.mp4", "video1.mp4"));
        assertTrue(id > 0);
    }

    @Test
    void findByIdReturnsInsertedVideo() {
        long id = repo.upsert(video("new/video1.mp4", "video1.mp4"));
        Optional<AvVideo> found = repo.findById(id);
        assertTrue(found.isPresent());
        assertEquals("new/video1.mp4", found.get().getRelativePath());
        assertEquals("video1.mp4", found.get().getFilename());
    }

    @Test
    void upsertOnConflictUpdatesMetadata() {
        long id = repo.upsert(video("new/video1.mp4", "video1.mp4", 1000L));
        long id2 = repo.upsert(video("new/video1.mp4", "video1.mp4", 2000L));
        assertEquals(id, id2);
        AvVideo found = repo.findById(id).orElseThrow();
        assertEquals(2000L, found.getSizeBytes());
    }

    // --- findByActress ---

    @Test
    void findByActressReturnsSortedByPath() {
        repo.upsert(video("z_last.mp4", "z_last.mp4"));
        repo.upsert(video("a_first.mp4", "a_first.mp4"));
        List<AvVideo> videos = repo.findByActress(actressId);
        assertEquals(2, videos.size());
        assertEquals("a_first.mp4", videos.get(0).getRelativePath());
        assertEquals("z_last.mp4", videos.get(1).getRelativePath());
    }

    @Test
    void findByActressReturnsEmptyWhenNone() {
        List<AvVideo> videos = repo.findByActress(actressId);
        assertTrue(videos.isEmpty());
    }

    // --- findByVolume ---

    @Test
    void findByVolumeReturnsAllVideosForVolume() {
        repo.upsert(video("file1.mp4", "file1.mp4"));
        repo.upsert(video("file2.mp4", "file2.mp4"));
        List<AvVideo> videos = repo.findByVolume("qnap_av");
        assertEquals(2, videos.size());
    }

    // --- bucket field ---

    @Test
    void bucketIsStoredAndRetrieved() {
        long id = repo.upsert(AvVideo.builder()
                .avActressId(actressId)
                .volumeId("qnap_av")
                .relativePath("old/video1.mp4")
                .filename("video1.mp4")
                .extension("mp4")
                .bucket("old")
                .lastSeenAt(LocalDateTime.now())
                .build());
        AvVideo found = repo.findById(id).orElseThrow();
        assertEquals("old", found.getBucket());
    }

    @Test
    void nullBucketMeansRootLevel() {
        long id = repo.upsert(AvVideo.builder()
                .avActressId(actressId)
                .volumeId("qnap_av")
                .relativePath("video1.mp4")
                .filename("video1.mp4")
                .extension("mp4")
                .bucket(null)
                .lastSeenAt(LocalDateTime.now())
                .build());
        AvVideo found = repo.findById(id).orElseThrow();
        assertNull(found.getBucket());
    }

    // --- deleteOrphanedByVolume ---

    @Test
    void deleteOrphanedRemovesRowsBeforeSyncStart() {
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        LocalDateTime syncStart = LocalDateTime.now();

        // Insert a video with a last_seen_at in the past
        repo.upsert(AvVideo.builder()
                .avActressId(actressId)
                .volumeId("qnap_av")
                .relativePath("old.mp4")
                .filename("old.mp4")
                .extension("mp4")
                .lastSeenAt(past)
                .build());
        // Insert a fresh video (seen during sync)
        repo.upsert(AvVideo.builder()
                .avActressId(actressId)
                .volumeId("qnap_av")
                .relativePath("fresh.mp4")
                .filename("fresh.mp4")
                .extension("mp4")
                .lastSeenAt(syncStart.plusSeconds(1))
                .build());

        repo.deleteOrphanedByVolume("qnap_av", syncStart);

        List<AvVideo> remaining = repo.findByVolume("qnap_av");
        assertEquals(1, remaining.size());
        assertEquals("fresh.mp4", remaining.get(0).getRelativePath());
    }

    @Test
    void deleteOrphanedKeepsRowsAfterSyncStart() {
        LocalDateTime syncStart = LocalDateTime.now();
        repo.upsert(video("file1.mp4", "file1.mp4"));

        repo.deleteOrphanedByVolume("qnap_av", syncStart.minusSeconds(1));
        assertEquals(1, repo.findByVolume("qnap_av").size());
    }

    // --- helpers ---

    private AvVideo video(String relativePath, String filename) {
        return video(relativePath, filename, null);
    }

    private AvVideo video(String relativePath, String filename, Long sizeBytes) {
        return AvVideo.builder()
                .avActressId(actressId)
                .volumeId("qnap_av")
                .relativePath(relativePath)
                .filename(filename)
                .extension(filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : null)
                .sizeBytes(sizeBytes)
                .lastSeenAt(LocalDateTime.now())
                .build();
    }
}
