package com.organizer3.media;

import com.organizer3.db.SchemaInitializer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class ThumbnailEvictorTest {

    private Connection connection;
    private Jdbi jdbi;
    private ThumbnailService thumbnailService;
    private ThumbnailEvictor evictor;
    @TempDir Path thumbsRoot;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        thumbnailService = new ThumbnailService(thumbsRoot, 8, 8080);
        evictor = new ThumbnailEvictor(jdbi, thumbnailService);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void coldNonStickyTitleIsEvicted() throws Exception {
        insertTitle("COLD-001", false, false, LocalDateTime.now().minusDays(40));
        seedThumbnails("COLD-001");

        int removed = evictor.sweep(30);

        assertEquals(1, removed);
        assertFalse(Files.exists(thumbsRoot.resolve("COLD-001")));
    }

    @Test
    void favoritedTitleIsNeverEvictedEvenWhenAncient() throws Exception {
        insertTitle("FAV-001", /*fav=*/true, /*book=*/false, LocalDateTime.now().minusDays(9999));
        seedThumbnails("FAV-001");

        assertEquals(0, evictor.sweep(30));
        assertTrue(Files.exists(thumbsRoot.resolve("FAV-001")));
    }

    @Test
    void bookmarkedTitleIsNeverEvicted() throws Exception {
        insertTitle("BOOK-001", false, true, null);
        seedThumbnails("BOOK-001");

        assertEquals(0, evictor.sweep(30));
        assertTrue(Files.exists(thumbsRoot.resolve("BOOK-001")));
    }

    @Test
    void favoritedActressMakesTitleSticky() throws Exception {
        long actressId = insertActress("Fav", true);
        long titleId = insertTitle("HER-001", false, false, LocalDateTime.now().minusDays(365));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses(title_id, actress_id) VALUES (?, ?)", titleId, actressId));
        seedThumbnails("HER-001");

        assertEquals(0, evictor.sweep(30));
        assertTrue(Files.exists(thumbsRoot.resolve("HER-001")));
    }

    @Test
    void recentlyVisitedTitleNotEvicted() throws Exception {
        insertTitle("HOT-001", false, false, LocalDateTime.now().minusDays(2));
        seedThumbnails("HOT-001");

        assertEquals(0, evictor.sweep(30));
        assertTrue(Files.exists(thumbsRoot.resolve("HOT-001")));
    }

    @Test
    void evictionDaysZeroDisablesSweep() throws Exception {
        insertTitle("OLD-001", false, false, LocalDateTime.now().minusDays(9999));
        seedThumbnails("OLD-001");

        assertEquals(0, evictor.sweep(0));
        assertTrue(Files.exists(thumbsRoot.resolve("OLD-001")));
    }

    // --- Grace window: favorite_cleared_at semantics ---

    @Test
    void unfavoritedTitleWithinGraceWindowStaysSticky() throws Exception {
        long id = insertTitle("GRACE-001", false, false, null);
        // Simulate: was favorited, un-favorited 10 days ago (within 30-day grace)
        jdbi.useHandle(h -> h.execute(
                "UPDATE titles SET favorite_cleared_at = datetime('now', '-10 days') WHERE id = ?", id));
        seedThumbnails("GRACE-001");

        assertEquals(0, evictor.sweep(30));
        assertTrue(Files.exists(thumbsRoot.resolve("GRACE-001")));
    }

    @Test
    void unfavoritedTitleAfterGraceExpiresIsEvicted() throws Exception {
        long id = insertTitle("EXPIRED-001", false, false, null);
        jdbi.useHandle(h -> h.execute(
                "UPDATE titles SET favorite_cleared_at = datetime('now', '-45 days') WHERE id = ?", id));
        seedThumbnails("EXPIRED-001");

        assertEquals(1, evictor.sweep(30));
        assertFalse(Files.exists(thumbsRoot.resolve("EXPIRED-001")));
    }

    @Test
    void unfavoritedActressWithinGraceKeepsHerTitlesSticky() throws Exception {
        long actressId = insertActress("GraceFav", /*fav=*/false);
        jdbi.useHandle(h -> h.execute(
                "UPDATE actresses SET favorite_cleared_at = datetime('now', '-15 days') WHERE id = ?", actressId));
        long titleId = insertTitle("HER-GRACE-001", false, false, LocalDateTime.now().minusDays(100));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses(title_id, actress_id) VALUES (?, ?)", titleId, actressId));
        seedThumbnails("HER-GRACE-001");

        assertEquals(0, evictor.sweep(30));
        assertTrue(Files.exists(thumbsRoot.resolve("HER-GRACE-001")));
    }

    @Test
    void unfavoritedActressAfterGraceDoesNotProtectTitle() throws Exception {
        long actressId = insertActress("ExpiredFav", false);
        jdbi.useHandle(h -> h.execute(
                "UPDATE actresses SET favorite_cleared_at = datetime('now', '-40 days') WHERE id = ?", actressId));
        long titleId = insertTitle("HER-OLD-001", false, false, LocalDateTime.now().minusDays(100));
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_actresses(title_id, actress_id) VALUES (?, ?)", titleId, actressId));
        seedThumbnails("HER-OLD-001");

        assertEquals(1, evictor.sweep(30));
        assertFalse(Files.exists(thumbsRoot.resolve("HER-OLD-001")));
    }

    // --- Trigger: favorite_cleared_at auto-maintenance ---

    @Test
    void unfavoritingTitleStampsFavoriteClearedAt() {
        long id = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num, favorite) "
              + "VALUES ('TRIG-001','TRIG-001','TRIG',1, 1)")
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        jdbi.useHandle(h -> h.execute("UPDATE titles SET favorite = 0 WHERE id = ?", id));

        String stamp = jdbi.withHandle(h -> h.createQuery(
                "SELECT favorite_cleared_at FROM titles WHERE id = ?")
                .bind(0, id).mapTo(String.class).one());
        assertNotNull(stamp, "favorite_cleared_at must be stamped on 1→0 transition");
    }

    @Test
    void refavoritingTitleClearsFavoriteClearedAt() {
        long id = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num, favorite) "
              + "VALUES ('TRIG-002','TRIG-002','TRIG',1, 1)")
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        jdbi.useHandle(h -> h.execute("UPDATE titles SET favorite = 0 WHERE id = ?", id));
        jdbi.useHandle(h -> h.execute("UPDATE titles SET favorite = 1 WHERE id = ?", id));

        String stamp = jdbi.withHandle(h -> h.createQuery(
                "SELECT favorite_cleared_at FROM titles WHERE id = ?")
                .bind(0, id).mapTo(String.class).findOne().orElse(null));
        assertNull(stamp, "favorite_cleared_at must be cleared on 0→1 transition (re-favorite)");
    }

    @Test
    void unfavoritingActressStampsFavoriteClearedAt() {
        long id = insertActress("TrigActress", /*fav=*/true);
        jdbi.useHandle(h -> h.execute("UPDATE actresses SET favorite = 0 WHERE id = ?", id));

        String stamp = jdbi.withHandle(h -> h.createQuery(
                "SELECT favorite_cleared_at FROM actresses WHERE id = ?")
                .bind(0, id).mapTo(String.class).one());
        assertNotNull(stamp);
    }

    @Test
    void favoritedTitlesAndNoTransitionLeaveStampNull() {
        // Just inserting with favorite=1 and never toggling it shouldn't stamp anything.
        long id = jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num, favorite) "
              + "VALUES ('QUIET-001','QUIET-001','QUIET',1, 1)")
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());

        String stamp = jdbi.withHandle(h -> h.createQuery(
                "SELECT favorite_cleared_at FROM titles WHERE id = ?")
                .bind(0, id).mapTo(String.class).findOne().orElse(null));
        assertNull(stamp);
    }

    @Test
    void orphanDirectoryNotInDbIsIgnoredByEvictor() throws Exception {
        // Evictor only targets known-cold titles; unknown codes are left for prune-thumbnails.
        seedThumbnails("UNKNOWN-001");
        assertEquals(0, evictor.sweep(30));
        assertTrue(Files.exists(thumbsRoot.resolve("UNKNOWN-001")));
    }

    // --- helpers ---

    private long insertTitle(String code, boolean fav, boolean book, LocalDateTime lastVisited) {
        String lv = lastVisited != null ? lastVisited.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO titles(code, base_code, label, seq_num, favorite, bookmark, last_visited_at, visit_count) "
              + "VALUES (:c, :c, :l, 1, :f, :b, :lv, 0)")
                .bind("c", code).bind("l", code.split("-")[0])
                .bind("f", fav ? 1 : 0).bind("b", book ? 1 : 0)
                .bind("lv", lv)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    private long insertActress(String name, boolean fav) {
        return jdbi.withHandle(h -> h.createUpdate(
                "INSERT INTO actresses(canonical_name, tier, first_seen_at, favorite) "
              + "VALUES (:n, 'LIBRARY', '2024-01-01', :f)")
                .bind("n", name).bind("f", fav ? 1 : 0)
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    /** Creates a non-empty thumbnail directory for the given title code. */
    private void seedThumbnails(String code) throws Exception {
        Path dir = thumbsRoot.resolve(code).resolve("video.mp4");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".count"), "4");
        Files.writeString(dir.resolve("thumb_01.jpg"), "fake");
    }
}
