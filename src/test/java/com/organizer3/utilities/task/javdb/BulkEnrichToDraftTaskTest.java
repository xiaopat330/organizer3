package com.organizer3.utilities.task.javdb;

import com.organizer3.db.SchemaInitializer;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.javdb.draft.DraftPopulator.PopulateResult;
import com.organizer3.javdb.draft.DraftPopulator.Status;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkEnrichToDraftTaskTest {

    private Connection connection;
    private Jdbi jdbi;
    private DraftPopulator draftPopulator;
    private BulkEnrichToDraftTask task;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();
        draftPopulator = mock(DraftPopulator.class);
        task = new BulkEnrichToDraftTask(jdbi, draftPopulator);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static TaskInputs inputWith(List<Long> ids) throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(ids);
        return TaskInputs.of("titleIds", json);
    }

    private static CapturingIO capture() { return new CapturingIO(); }

    /** Seeds a draft_titles row so hasDraft() returns true for that titleId. */
    private void seedDraft(long titleId) {
        // Must also have a titles row for the FK constraint.
        jdbi.useHandle(h -> {
            long existing = h.createQuery("SELECT COUNT(*) FROM titles WHERE id = :id")
                    .bind("id", titleId).mapTo(Long.class).one();
            if (existing == 0) {
                h.createUpdate("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (:id, :code, 'XX', 'XX', 1)")
                        .bind("id", titleId)
                        .bind("code", "XX-" + titleId)
                        .execute();
            }
            h.createUpdate("""
                    INSERT OR IGNORE INTO draft_titles
                        (title_id, code, upstream_changed, created_at, updated_at)
                    VALUES (:titleId, :code, 0, '2025-01-01', '2025-01-01')
                    """)
                    .bind("titleId", titleId)
                    .bind("code", "XX-" + titleId)
                    .execute();
        });
    }

    /** Seeds a title_javdb_enrichment row so hasCuratedEnrichment() returns true. */
    private void seedEnrichment(long titleId) {
        jdbi.useHandle(h -> {
            long existing = h.createQuery("SELECT COUNT(*) FROM titles WHERE id = :id")
                    .bind("id", titleId).mapTo(Long.class).one();
            if (existing == 0) {
                h.createUpdate("INSERT INTO titles (id, code, base_code, label, seq_num) VALUES (:id, :code, 'XX', 'XX', 1)")
                        .bind("id", titleId)
                        .bind("code", "XX-" + titleId)
                        .execute();
            }
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_javdb_enrichment
                        (title_id, javdb_slug, fetched_at, resolver_source, confidence, cast_validated)
                    VALUES (:titleId, 'someSlug', '2025-01-01', 'actress_filmography', 'HIGH', 0)
                    """)
                    .bind("titleId", titleId)
                    .execute();
        });
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void emptyInputDoesNothing() throws Exception {
        CapturingIO io = capture();
        task.run(inputWith(List.of()), io);

        verify(draftPopulator, never()).populate(anyLong());
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("nothing")));
    }

    @Test
    void successfulRunCreatesExpectedDrafts() throws Exception {
        when(draftPopulator.populate(1L)).thenReturn(new PopulateResult(Status.CREATED, 100L));
        when(draftPopulator.populate(2L)).thenReturn(new PopulateResult(Status.CREATED, 101L));

        CapturingIO io = capture();
        task.run(inputWith(List.of(1L, 2L)), io);

        verify(draftPopulator).populate(1L);
        verify(draftPopulator).populate(2L);
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("2 draft(s) created")));
    }

    @Test
    void skipsAlreadyDraftedTitles() throws Exception {
        seedDraft(1L);
        when(draftPopulator.populate(2L)).thenReturn(new PopulateResult(Status.CREATED, 101L));

        CapturingIO io = capture();
        task.run(inputWith(List.of(1L, 2L)), io);

        // title 1 excluded in plan phase — populate never called for it.
        verify(draftPopulator, never()).populate(1L);
        verify(draftPopulator).populate(2L);
        // Plan summary mentions the exclusion.
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("already have drafts")));
    }

    @Test
    void skipsAlreadyCuratedTitles() throws Exception {
        seedEnrichment(1L);
        when(draftPopulator.populate(2L)).thenReturn(new PopulateResult(Status.CREATED, 101L));

        CapturingIO io = capture();
        task.run(inputWith(List.of(1L, 2L)), io);

        verify(draftPopulator, never()).populate(1L);
        verify(draftPopulator).populate(2L);
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("already curated")));
    }

    @Test
    void idempotentRerun_secondRunSkipsAllAsDrafted() throws Exception {
        // First run: create drafts for both titles.
        seedDraft(1L);
        seedDraft(2L);

        CapturingIO io = capture();
        task.run(inputWith(List.of(1L, 2L)), io);

        // Both excluded in plan; populate never called.
        verify(draftPopulator, never()).populate(anyLong());
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("already have drafts")));
    }

    @Test
    void cancellationMidFlightPreservesDraftsSoFar() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(draftPopulator.populate(1L)).thenReturn(new PopulateResult(Status.CREATED, 100L));
        when(draftPopulator.populate(2L)).thenAnswer(inv -> {
            cancelled.set(true);  // signal cancel after first title is done
            return new PopulateResult(Status.CREATED, 101L);
        });
        when(draftPopulator.populate(3L)).thenReturn(new PopulateResult(Status.CREATED, 102L));

        CapturingIO io = new CapturingIO() {
            @Override public boolean isCancellationRequested() {
                return cancelled.get();
            }
        };

        task.run(inputWith(List.of(1L, 2L, 3L)), io);

        // populate(1L) and populate(2L) were called; populate(3L) was not (cancelled after 2L).
        verify(draftPopulator).populate(1L);
        verify(draftPopulator).populate(2L);
        verify(draftPopulator, never()).populate(3L);
        // Log mentions cancellation.
        assertTrue(io.logs.stream().anyMatch(l -> l.contains("Cancelled")));
    }

    @Test
    void javdbErrorCountedAsFailureButDoesNotAbort() throws Exception {
        when(draftPopulator.populate(1L)).thenReturn(new PopulateResult(Status.JAVDB_ERROR, null));
        when(draftPopulator.populate(2L)).thenReturn(new PopulateResult(Status.CREATED, 101L));

        CapturingIO io = capture();
        task.run(inputWith(List.of(1L, 2L)), io);

        verify(draftPopulator).populate(1L);
        verify(draftPopulator).populate(2L);
        // Summary shows 1 failure and 1 created.
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("1 draft(s) created")));
        assertTrue(io.endSummaries.stream().anyMatch(s -> s.contains("1 failure")));
    }

    @Test
    void invalidJsonInputReturnsFailedPhase() {
        CapturingIO io = capture();
        task.run(TaskInputs.of("titleIds", "not-valid-json"), io);

        verify(draftPopulator, never()).populate(anyLong());
        assertTrue(io.endStatuses.stream().anyMatch(s -> s.equals("failed")));
    }

    @Test
    void populateRuntimeExceptionCountedAsFailure() throws Exception {
        when(draftPopulator.populate(1L)).thenThrow(new RuntimeException("network timeout"));
        when(draftPopulator.populate(2L)).thenReturn(new PopulateResult(Status.CREATED, 101L));

        CapturingIO io = capture();
        task.run(inputWith(List.of(1L, 2L)), io);

        verify(draftPopulator).populate(1L);
        verify(draftPopulator).populate(2L);
        assertTrue(io.logs.stream().anyMatch(l -> l.contains("network timeout")));
    }

    // ── Capture helper ────────────────────────────────────────────────────────

    static class CapturingIO implements TaskIO {
        final List<String> logs        = new ArrayList<>();
        final List<String> endSummaries= new ArrayList<>();
        final List<String> endStatuses = new ArrayList<>();

        @Override public void phaseStart(String id, String label) {}
        @Override public void phaseProgress(String id, int current, int total, String detail) {}
        @Override public void phaseLog(String id, String line) { logs.add(line); }
        @Override public void phaseEnd(String id, String status, String summary) {
            endSummaries.add(summary);
            endStatuses.add(status);
        }
        @Override public boolean isCancellationRequested() { return false; }
    }
}
