package com.organizer3.avstars.repository.jdbi;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbiAvScreenshotRepositoryTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiAvScreenshotRepository repo;
    private long videoId;
    private long actressId;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO volumes (id, structure_type) VALUES ('qnap_av', 'avstars')");
            actressId = h.createUpdate("""
                    INSERT INTO av_actresses (volume_id, folder_name, stage_name, first_seen_at,
                        video_count, total_size_bytes)
                    VALUES ('qnap_av', 'Test Actress', 'Test Actress', '2024-01-01T00:00:00', 0, 0)
                    """).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            videoId = h.createUpdate("""
                    INSERT INTO av_videos (av_actress_id, volume_id, relative_path, filename, last_seen_at)
                    VALUES (?, 'qnap_av', 'v1.mp4', 'v1.mp4', '2024-01-01T00:00:00')
                    """).bind(0, actressId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
        });
        repo = new JdbiAvScreenshotRepository(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void insertAndFindByVideoId() {
        repo.insert(videoId, 0, "/path/frame0.jpg");
        repo.insert(videoId, 1, "/path/frame1.jpg");

        var shots = repo.findByVideoId(videoId);
        assertEquals(2, shots.size());
        assertEquals(0, shots.get(0).getSeq());
        assertEquals("/path/frame0.jpg", shots.get(0).getPath());
        assertEquals(1, shots.get(1).getSeq());
    }

    @Test
    void insertIsUpsertOnVideoIdAndSeqConflict() {
        repo.insert(videoId, 0, "/path/original.jpg");
        // Insert same (videoId, seq) with a different path — must not throw
        assertDoesNotThrow(() -> repo.insert(videoId, 0, "/path/replacement.jpg"));

        var shots = repo.findByVideoId(videoId);
        assertEquals(1, shots.size());
        assertEquals("/path/replacement.jpg", shots.get(0).getPath());
    }

    @Test
    void countByVideoId() {
        assertEquals(0, repo.countByVideoId(videoId));
        repo.insert(videoId, 0, "/path/frame0.jpg");
        repo.insert(videoId, 1, "/path/frame1.jpg");
        assertEquals(2, repo.countByVideoId(videoId));
    }

    @Test
    void deleteByVideoIdRemovesAllRows() {
        repo.insert(videoId, 0, "/path/frame0.jpg");
        repo.insert(videoId, 1, "/path/frame1.jpg");
        repo.deleteByVideoId(videoId);
        assertEquals(0, repo.countByVideoId(videoId));
        assertTrue(repo.findByVideoId(videoId).isEmpty());
    }

    @Test
    void findCountsByVideoIds() {
        repo.insert(videoId, 0, "/path/frame0.jpg");
        repo.insert(videoId, 1, "/path/frame1.jpg");

        var counts = repo.findCountsByVideoIds(List.of(videoId));
        assertEquals(2, counts.get(videoId));
    }

    @Test
    void findCountsByVideoIdsReturnsEmptyForUnknownVideo() {
        var counts = repo.findCountsByVideoIds(List.of(9999L));
        assertFalse(counts.containsKey(9999L));
    }

    @Test
    void deleteByActressIdRemovesAllScreenshotsForHerVideos() {
        // Add a second video for the same actress.
        long video2 = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO av_videos (av_actress_id, volume_id, relative_path, filename, last_seen_at)
                VALUES (?, 'qnap_av', 'v2.mp4', 'v2.mp4', '2024-01-01T00:00:00')
                """).bind(0, actressId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        repo.insert(videoId, 0, "/p/v1-0.jpg");
        repo.insert(videoId, 1, "/p/v1-1.jpg");
        repo.insert(video2,  0, "/p/v2-0.jpg");

        int deleted = repo.deleteByActressId(actressId);
        assertEquals(3, deleted);
        assertEquals(0, repo.countByVideoId(videoId));
        assertEquals(0, repo.countByVideoId(video2));
    }

    @Test
    void deleteByActressIdLeavesOtherActressesScreenshotsAlone() {
        long otherActress = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO av_actresses (volume_id, folder_name, stage_name, first_seen_at,
                    video_count, total_size_bytes)
                VALUES ('qnap_av', 'Other', 'Other', '2024-01-01T00:00:00', 0, 0)
                """).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long otherVideo = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO av_videos (av_actress_id, volume_id, relative_path, filename, last_seen_at)
                VALUES (?, 'qnap_av', 'o.mp4', 'o.mp4', '2024-01-01T00:00:00')
                """).bind(0, otherActress).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        repo.insert(videoId,    0, "/p/v1-0.jpg");
        repo.insert(otherVideo, 0, "/p/o-0.jpg");

        int deleted = repo.deleteByActressId(actressId);
        assertEquals(1, deleted);
        assertEquals(1, repo.countByVideoId(otherVideo));
    }

    @Test
    void countVideosWithScreenshotsByActresses() {
        // Set up: actress1 has videoId (with 2 screenshots) + a second video without screenshots.
        // actress2 has one video without screenshots. actress3 has none.
        long video2NoShots = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO av_videos (av_actress_id, volume_id, relative_path, filename, last_seen_at)
                VALUES (?, 'qnap_av', 'v2.mp4', 'v2.mp4', '2024-01-01T00:00:00')
                """).bind(0, actressId).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        long actress2 = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO av_actresses (volume_id, folder_name, stage_name, first_seen_at,
                    video_count, total_size_bytes)
                VALUES ('qnap_av', 'A2', 'A2', '2024-01-01T00:00:00', 0, 0)
                """).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO av_videos (av_actress_id, volume_id, relative_path, filename, last_seen_at) " +
                "VALUES (?, 'qnap_av', 'v3.mp4', 'v3.mp4', '2024-01-01T00:00:00')", actress2));
        long actress3 = jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO av_actresses (volume_id, folder_name, stage_name, first_seen_at,
                    video_count, total_size_bytes)
                VALUES ('qnap_av', 'A3', 'A3', '2024-01-01T00:00:00', 0, 0)
                """).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
        repo.insert(videoId, 0, "/p/v1-0.jpg");
        repo.insert(videoId, 1, "/p/v1-1.jpg");
        // video2NoShots intentionally has no screenshots
        assertEquals(0, repo.countByVideoId(video2NoShots));

        var result = repo.countVideosWithScreenshotsByActresses(List.of(actressId, actress2, actress3));
        assertEquals(1, result.get(actressId));   // 1 video with screenshots (videoId), the other has none
        assertEquals(0, result.get(actress2));
        assertEquals(0, result.get(actress3));
    }

    @Test
    void countVideosWithScreenshotsByActressesEmptyInput() {
        assertTrue(repo.countVideosWithScreenshotsByActresses(List.of()).isEmpty());
    }

    @Test
    void findFirstSeqByVideoIds() {
        repo.insert(videoId, 3, "/path/frame3.jpg");
        repo.insert(videoId, 0, "/path/frame0.jpg");
        repo.insert(videoId, 1, "/path/frame1.jpg");

        var result = repo.findFirstSeqByVideoIds(List.of(videoId));
        assertEquals(0, result.get(videoId));
    }
}
