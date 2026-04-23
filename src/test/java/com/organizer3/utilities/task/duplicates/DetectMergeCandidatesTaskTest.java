package com.organizer3.utilities.task.duplicates;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.model.MergeCandidate;
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

class DetectMergeCandidatesTaskTest {

    private Connection connection;
    private Jdbi jdbi;
    private JdbiMergeCandidateRepository repo;
    private DetectMergeCandidatesTask task;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        repo = new JdbiMergeCandidateRepository(jdbi);
        Clock clock = Clock.fixed(Instant.parse("2026-04-23T00:00:00Z"), ZoneOffset.UTC);
        task = new DetectMergeCandidatesTask(repo, jdbi, clock);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    private void insertTitle(String code, String baseCode) {
        jdbi.useHandle(h -> h.execute(
                "INSERT INTO titles (code, base_code) VALUES (?, ?)", code, baseCode));
    }

    private static TaskInputs emptyInputs() { return new TaskInputs(Map.of()); }

    // ── hasSuffix helper ──────────────────────────────────────────────────────

    @Test
    void hasSuffixDetectsUnderscoreVariants() {
        assertTrue(DetectMergeCandidatesTask.hasSuffix("ABP-123_U"));
        assertTrue(DetectMergeCandidatesTask.hasSuffix("PRED-456_4K"));
        assertFalse(DetectMergeCandidatesTask.hasSuffix("ABP-123"));
        assertFalse(DetectMergeCandidatesTask.hasSuffix("ONED-001"));
        assertFalse(DetectMergeCandidatesTask.hasSuffix("no-dash-code"));
    }

    // ── detection tests ───────────────────────────────────────────────────────

    @Test
    void noSharedBaseCode_noCandidatesInserted() {
        insertTitle("ABP-001", "ABP-00001");
        insertTitle("PRED-001", "PRED-00001");

        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);

        assertEquals(0, repo.listPending().size());
        assertEquals("ok", io.status("scan"));
    }

    @Test
    void twoCodesShareBaseCode_codeNormalizationCandidate() {
        insertTitle("ONED-001", "ONED-00001");
        insertTitle("ONED-01", "ONED-00001");

        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);

        List<MergeCandidate> pending = repo.listPending();
        assertEquals(1, pending.size());
        assertEquals("code-normalization", pending.get(0).getConfidence());
        assertEquals("ok", io.status("populate"));
    }

    @Test
    void suffixVariant_classifiedAsVariantSuffix() {
        insertTitle("ABP-123", "ABP-00123");
        insertTitle("ABP-123_U", "ABP-00123");

        CapturingIO io = new CapturingIO();
        task.run(emptyInputs(), io);

        List<MergeCandidate> pending = repo.listPending();
        assertEquals(1, pending.size());
        assertEquals("variant-suffix", pending.get(0).getConfidence());
    }

    @Test
    void threeCodesShareBaseCode_threePairsGenerated() {
        insertTitle("ONED-001", "ONED-00001");
        insertTitle("ONED-01",  "ONED-00001");
        insertTitle("ONED-1",   "ONED-00001");

        task.run(emptyInputs(), new CapturingIO());

        assertEquals(3, repo.listPending().size());
    }

    @Test
    void decidedCandidatesNotOverwrittenOnReScan() {
        insertTitle("ONED-001", "ONED-00001");
        insertTitle("ONED-01",  "ONED-00001");

        task.run(emptyInputs(), new CapturingIO());
        long id = repo.listPending().get(0).getId();
        repo.decide(id, "DISMISS", null, Instant.now().toString());

        // Re-run detection
        task.run(emptyInputs(), new CapturingIO());

        // Dismissed candidate must survive
        assertEquals(0, repo.listPending().size());
    }

    @Test
    void nullBaseCodeTitlesIgnored() {
        jdbi.useHandle(h -> h.execute("INSERT INTO titles (code, base_code) VALUES ('NOCODE-1', NULL)"));
        jdbi.useHandle(h -> h.execute("INSERT INTO titles (code, base_code) VALUES ('NOCODE-2', NULL)"));

        task.run(emptyInputs(), new CapturingIO());

        assertEquals(0, repo.listPending().size());
    }

    // ── minimal TaskIO capture ────────────────────────────────────────────────

    static final class CapturingIO implements TaskIO {
        private final Map<String, String> statuses  = new HashMap<>();
        private final Map<String, String> summaries = new HashMap<>();
        private final Map<String, List<String>> logs = new HashMap<>();

        @Override public void phaseStart(String id, String label)  { logs.put(id, new ArrayList<>()); }
        @Override public void phaseProgress(String id, int c, int t, String detail) {}
        @Override public void phaseLog(String id, String line) {
            logs.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
        }
        @Override public void phaseEnd(String id, String status, String summary) {
            statuses.put(id, status);
            summaries.put(id, summary);
        }

        String status(String phase)  { return statuses.getOrDefault(phase, ""); }
        String summary(String phase) { return summaries.getOrDefault(phase, ""); }
    }
}
