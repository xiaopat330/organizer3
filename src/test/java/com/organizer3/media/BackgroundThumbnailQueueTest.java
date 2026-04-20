package com.organizer3.media;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundThumbnailQueueTest {

    private Connection connection;
    private Jdbi jdbi;
    private BackgroundThumbnailQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO volumes (id, structure_type) VALUES ('vol-a', 'conventional')"));
        queue = new BackgroundThumbnailQueue(jdbi);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void titlesWithNoAttentionSignalAreExcluded() {
        long tid = insertTitle("NOPE-001", false, false, null, 0);
        insertVideo(tid, "nope.mp4", null);

        List<BackgroundThumbnailQueue.Candidate> results = queue.topCandidates(100);
        assertTrue(results.isEmpty(), "zero-signal title must not appear in the queue");
    }

    @Test
    void bookmarkedTitleOutranksMerelyFavoritedTitle() {
        long tBook = insertTitle("BOOK-001", false, true, null, 0);
        long tFav  = insertTitle("FAV-001",  true,  false, null, 0);
        insertVideo(tBook, "b.mp4", null);
        insertVideo(tFav,  "f.mp4", null);

        List<BackgroundThumbnailQueue.Candidate> results = queue.topCandidates(100);
        assertEquals(2, results.size());
        assertEquals("BOOK-001", results.get(0).getTitleCode());
        assertEquals("FAV-001",  results.get(1).getTitleCode());
    }

    @Test
    void favoritedActressLiftsAllHerTitles() {
        long actressFav = insertActress("Fav Actress", true);
        long titleA = insertTitle("ACT-001", false, false, null, 0);
        insertTitleActress(titleA, actressFav);
        insertVideo(titleA, "a.mp4", null);

        // Control: title with no actress and no signal
        long titleB = insertTitle("CTRL-001", false, false, null, 0);
        insertVideo(titleB, "b.mp4", null);

        List<BackgroundThumbnailQueue.Candidate> results = queue.topCandidates(100);
        assertEquals(1, results.size(), "only the actress-linked title should appear");
        assertEquals("ACT-001", results.get(0).getTitleCode());
    }

    @Test
    void recentlyVisitedTitleBeatsOlderVisitedTitle() {
        String recent = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String older  = LocalDateTime.now().minusDays(60).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        long tRecent = insertTitle("REC-001", false, false, recent, 1);
        long tOld    = insertTitle("OLD-001", false, false, older,  1);
        insertVideo(tRecent, "r.mp4", null);
        insertVideo(tOld,    "o.mp4", null);

        List<BackgroundThumbnailQueue.Candidate> results = queue.topCandidates(100);
        assertEquals("REC-001", results.get(0).getTitleCode());
    }

    @Test
    void limitIsHonored() {
        for (int i = 0; i < 5; i++) {
            long tid = insertTitle("MANY-" + i, false, true, null, 0);
            insertVideo(tid, "v" + i + ".mp4", null);
        }
        assertEquals(2, queue.topCandidates(2).size());
    }

    // --- helpers ---

    private long insertTitle(String code, boolean favorite, boolean bookmark,
                             String lastVisitedAt, int visitCount) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num, favorite, bookmark, last_visited_at, visit_count) "
              + "VALUES (:code, :bc, :label, 1, :fav, :book, :lv, :vc)")
                .bind("code", code)
                .bind("bc", code)
                .bind("label", code.split("-")[0])
                .bind("fav", favorite ? 1 : 0)
                .bind("book", bookmark ? 1 : 0)
                .bind("lv", lastVisitedAt)
                .bind("vc", visitCount)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
    }

    private long insertActress(String name, boolean favorite) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses(canonical_name, tier, first_seen_at, favorite) "
              + "VALUES (:n, 'LIBRARY', '2024-01-01', :f)")
                .bind("n", name)
                .bind("f", favorite ? 1 : 0)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
    }

    private void insertTitleActress(long titleId, long actressId) {
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO title_actresses(title_id, actress_id) VALUES (:t, :a)")
                .bind("t", titleId).bind("a", actressId).execute());
    }

    private void insertVideo(long titleId, String filename, String lastSeenAt) {
        String ls = lastSeenAt != null ? lastSeenAt : "2024-01-01";
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO videos(title_id, volume_id, filename, path, last_seen_at) "
              + "VALUES (:t, 'vol-a', :f, :p, :ls)")
                .bind("t", titleId).bind("f", filename)
                .bind("p", "/x/" + filename).bind("ls", ls)
                .execute());
    }
}
