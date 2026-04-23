package com.organizer3.utilities.task.duplicates;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.repository.jdbi.JdbiMergeCandidateRepository;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecuteMergeTaskTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiMergeCandidateRepository repo;
    private ExecuteMergeTask task;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-23T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiMergeCandidateRepository(jdbi);
        task = new ExecuteMergeTask(repo, jdbi, CLOCK);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long insertTitle(String code) {
        return jdbi.withHandle(h -> h.createUpdate(
                        "INSERT INTO titles (code, base_code, label, seq_num) VALUES (:code, :code, 'LBL', 1)")
                .bind("code", code)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class).one());
    }

    private void insertVolume(String id) {
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO volumes (id, structure_type) VALUES (?, 'collections')", id));
    }

    private void insertLocation(long titleId, String volumeId, String partitionId, String path) {
        insertVolume(volumeId);
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO title_locations (title_id, volume_id, partition_id, path, last_seen_at) VALUES (?,?,?,?,?)",
                titleId, volumeId, partitionId, path, "2026-01-01T00:00:00Z"));
    }

    private void insertVideo(long titleId, String volumeId, String filename) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO videos (title_id, volume_id, filename, path, last_seen_at) VALUES (?,?,?,?,?)",
                titleId, volumeId, filename, "/some/" + filename, "2026-01-01T00:00:00Z"));
    }

    private void insertActress(long titleId, String name) {
        long actressId = jdbi.withHandle(h -> h.createUpdate(
                        "INSERT OR IGNORE INTO actresses (canonical_name, tier, first_seen_at) VALUES (:n,'regular','2026-01-01')")
                .bind("n", name)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class).one());
        jdbi.useHandle(h -> h.execute(
                "INSERT OR IGNORE INTO title_actresses (title_id, actress_id) VALUES (?,?)", titleId, actressId));
    }

    private void insertWatchHistory(String titleCode, String watchedAt) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO watch_history (title_code, watched_at) VALUES (?,?)", titleCode, watchedAt));
    }

    private void decideMerge(long candidateId, String winnerCode) {
        repo.decide(candidateId, "MERGE", winnerCode, "2026-04-23T00:00:00Z");
    }

    private static TaskInputs emptyInputs() { return new TaskInputs(Map.of()); }

    // ── no-op test ────────────────────────────────────────────────────────────

    @Test
    void noPendingMerges_isNoOp() throws Exception {
        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);
        assertEquals("ok", io.status("plan"));
        assertNull(io.status("execute"));
    }

    // ── basic merge ───────────────────────────────────────────────────────────

    @Test
    void basicMerge_loserTitleDeleted() throws Exception {
        long winnerId = insertTitle("ABP-001");
        long loserId  = insertTitle("ABP-01");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        long candidateId = repo.listPending().get(0).getId();
        decideMerge(candidateId, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        boolean loserExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles WHERE id = ?")
                        .bind(0, loserId).mapTo(Integer.class).one() > 0);
        assertFalse(loserExists, "loser title row should be deleted");

        boolean winnerExists = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM titles WHERE id = ?")
                        .bind(0, winnerId).mapTo(Integer.class).one() > 0);
        assertTrue(winnerExists, "winner title row must survive");
    }

    @Test
    void basicMerge_candidateMarkedExecuted() throws Exception {
        insertTitle("ABP-001");
        insertTitle("ABP-01");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        long candidateId = repo.listPending().get(0).getId();
        decideMerge(candidateId, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        assertEquals(0, repo.listPendingMerge().size());
    }

    // ── data reparenting ──────────────────────────────────────────────────────

    @Test
    void locations_reparentedToWinner() throws Exception {
        long winnerId = insertTitle("ABP-001");
        long loserId  = insertTitle("ABP-01");

        insertLocation(winnerId, "vol1", "A", "/vol1/ABP-001");
        insertLocation(loserId,  "vol2", "A", "/vol2/ABP-01");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        long id = repo.listPending().get(0).getId();
        decideMerge(id, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        int winnerLocations = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_locations WHERE title_id = ?")
                        .bind(0, winnerId).mapTo(Integer.class).one());
        assertEquals(2, winnerLocations, "winner should have both locations");

        int loserLocations = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_locations WHERE title_id = ?")
                        .bind(0, loserId).mapTo(Integer.class).one());
        assertEquals(0, loserLocations, "loser locations should be deleted");
    }

    @Test
    void videos_reparentedToWinner() throws Exception {
        long winnerId = insertTitle("ABP-001");
        long loserId  = insertTitle("ABP-01");

        insertVideo(loserId, "vol1", "ABP-01.mp4");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        long id = repo.listPending().get(0).getId();
        decideMerge(id, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        int winnerVideos = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM videos WHERE title_id = ?")
                        .bind(0, winnerId).mapTo(Integer.class).one());
        assertEquals(1, winnerVideos);
    }

    @Test
    void actresses_reparentedToWinner() throws Exception {
        long winnerId = insertTitle("ABP-001");
        long loserId  = insertTitle("ABP-01");

        insertActress(loserId, "Unique Actress");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        long id = repo.listPending().get(0).getId();
        decideMerge(id, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        int winnerActresses = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE title_id = ?")
                        .bind(0, winnerId).mapTo(Integer.class).one());
        assertEquals(1, winnerActresses);
    }

    @Test
    void watchHistory_reparentedToWinner() throws Exception {
        insertTitle("ABP-001");
        insertTitle("ABP-01");

        insertWatchHistory("ABP-01", "2026-01-01T10:00:00Z");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        long id = repo.listPending().get(0).getId();
        decideMerge(id, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        int winnerHistory = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM watch_history WHERE title_code = 'ABP-001'")
                        .mapTo(Integer.class).one());
        assertEquals(1, winnerHistory);

        int loserHistory = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM watch_history WHERE title_code = 'ABP-01'")
                        .mapTo(Integer.class).one());
        assertEquals(0, loserHistory);
    }

    @Test
    void watchHistory_deduplicatedWhenBothHaveSameTimestamp() throws Exception {
        insertTitle("ABP-001");
        insertTitle("ABP-01");

        // Both have a watch entry at the same time — the loser's should be discarded
        insertWatchHistory("ABP-001", "2026-01-01T10:00:00Z");
        insertWatchHistory("ABP-01",  "2026-01-01T10:00:00Z");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        long id = repo.listPending().get(0).getId();
        decideMerge(id, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        int winnerHistory = jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM watch_history WHERE title_code = 'ABP-001'")
                        .mapTo(Integer.class).one());
        assertEquals(1, winnerHistory, "duplicate watch entry should be deduplicated to one");
    }

    @Test
    void staleMergeCandidatesForLoser_deleted() throws Exception {
        insertTitle("ABP-001");
        insertTitle("ABP-01");
        insertTitle("ZZZ-999");

        repo.insertIfAbsent("ABP-001", "ABP-01", "code-normalization", "2026-04-23T00:00:00Z");
        // Another stale candidate involving the loser
        repo.insertIfAbsent("ABP-01", "ZZZ-999", "code-normalization", "2026-04-23T00:00:00Z");

        long id = repo.listPending().stream()
                .filter(c -> "ABP-001".equals(c.getTitleCodeA())).findFirst().orElseThrow().getId();
        decideMerge(id, "ABP-001");

        task.run(emptyInputs(), new CapturingIO());

        // The stale ABP-01 ↔ ZZZ-999 candidate should be gone
        assertFalse(repo.find("ABP-01", "ZZZ-999").isPresent());
    }

    // ── minimal TaskIO capture ────────────────────────────────────────────────

    static final class CapturingIO implements TaskIO {
        private final Map<String, String> statuses  = new HashMap<>();
        private final Map<String, List<String>> logs = new HashMap<>();

        @Override public void phaseStart(String id, String label) { logs.put(id, new ArrayList<>()); }
        @Override public void phaseProgress(String id, int c, int t, String detail) {}
        @Override public void phaseLog(String id, String line) {
            logs.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
        }
        @Override public void phaseEnd(String id, String status, String summary) {
            statuses.put(id, status);
        }

        String status(String phase) { return statuses.get(phase); }
    }
}
