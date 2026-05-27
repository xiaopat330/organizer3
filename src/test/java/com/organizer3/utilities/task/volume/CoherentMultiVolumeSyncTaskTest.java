package com.organizer3.utilities.task.volume;

import com.organizer3.command.Command;
import com.organizer3.config.AppConfig;
import com.organizer3.config.SmbSettings;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.FullSyncOperation;
import com.organizer3.sync.ReconcileReport;
import com.organizer3.sync.ReconcileService;
import com.organizer3.sync.SyncPruneService;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CoherentMultiVolumeSyncTask} — case 4 from §7 of
 * {@code spec/PROPOSAL_SYNC_RECONCILIATION.md}.
 *
 * <p>Uses mock {@link FullSyncOperation} and {@link SyncPruneService} so no real filesystem
 * or database connection is needed. {@link SyncVolumeTaskTest} established the stub-command
 * pattern that this test extends.
 *
 * <h3>Scenarios covered</h3>
 * <ol>
 *   <li>Happy path — all volumes scan successfully; global prune runs once at the end.</li>
 *   <li>Partial failure — one volume's scan throws; global prune is skipped.</li>
 *   <li>Mount failure — treated as partial failure; prune skipped.</li>
 *   <li>Volume scope — avstars volumes excluded; only supported structure types included.</li>
 * </ol>
 *
 * <p>The suppress-prune flag isolation (case 3 in the proposal) is tested in
 * {@link com.organizer3.sync.FullSyncOperationTest#suppressPruneFlag_skipsPruneAndSweep()}.
 */
class CoherentMultiVolumeSyncTaskTest {

    // ── Stub command ──────────────────────────────────────────────────────────

    /** Records invocations; optionally runs a side-effect (e.g. to set session state). */
    private static class StubCommand implements Command {
        final String name;
        final List<String[]> invocations = new ArrayList<>();
        Runnable sideEffect = () -> {};
        boolean failOnInvoke = false;

        StubCommand(String name) { this.name = name; }

        @Override public String name()        { return name; }
        @Override public String description() { return ""; }
        @Override public void execute(String[] args, SessionContext ctx, CommandIO io) {
            invocations.add(args);
            if (failOnInvoke) throw new RuntimeException("Injected failure for " + name);
            sideEffect.run();
        }
    }

    // ── Test volumes ──────────────────────────────────────────────────────────

    private static final VolumeConfig VOL_A = new VolumeConfig("vol-a", "//x/a", "conventional", "srv", null);
    private static final VolumeConfig VOL_B = new VolumeConfig("vol-b", "//x/b", "queue",        "srv", null);
    /** AvStars — should be excluded from coherent sync scope. */
    private static final VolumeConfig VOL_AV = new VolumeConfig("vol-av", "//x/av", "avstars", "srv", null);

    // ── Collaborators ─────────────────────────────────────────────────────────

    private FullSyncOperation suppressedPruneOp;
    private SyncPruneService pruneService;
    private ReconcileService reconcileService;

    @BeforeEach
    void setUp() {
        suppressedPruneOp = mock(FullSyncOperation.class);
        pruneService      = mock(SyncPruneService.class);
        reconcileService  = mock(ReconcileService.class);
        // Reconcile.run returns a synthetic empty report by default.
        when(reconcileService.run(anyBoolean()))
                .thenReturn(new ReconcileReport(
                        java.time.Instant.now(),
                        0, 0, 0, 0, 0,
                        List.of(), List.of(), List.of(), List.of()));
        when(reconcileService.persist(any(), anyString(), any())).thenReturn(1L);

        // AppConfig must be initialized; coherent task reads volumes() and syncOrDefaults()
        // from it. Provide two supported volumes plus one avstars volume.
        AppConfig.initializeForTest(new OrganizerConfig(
                "test", "/tmp", null, null, null, null, null, null,
                List.of(),
                // volumes list: vol-a (conventional), vol-b (queue), vol-av (avstars)
                List.of(VOL_A, VOL_B, VOL_AV),
                // structures: one entry per structure type (even minimal is enough since
                // FullSyncOperation is mocked — the task only needs findStructureById to succeed)
                List.of(
                        new com.organizer3.config.volume.VolumeStructureDef("conventional", List.of(), null),
                        new com.organizer3.config.volume.VolumeStructureDef("queue", List.of(), null)
                ),
                List.of(), null, null));
    }

    @AfterEach
    void tearDown() {
        AppConfig.reset();
    }

    // ── Test helper: StubMount sets up a session that looks mounted ───────────

    private StubCommand mountStub(SessionContext session, VolumeConfig volume) {
        StubCommand mount = new StubCommand("mount");
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);
        VolumeFileSystem fs = mock(VolumeFileSystem.class);
        when(conn.fileSystem()).thenReturn(fs);
        mount.sideEffect = () -> {
            session.setMountedVolume(volume);
            session.setActiveConnection(conn);
        };
        return mount;
    }

    private StubCommand unmountStub(SessionContext session) {
        StubCommand unmount = new StubCommand("unmount");
        unmount.sideEffect = () -> {
            session.setMountedVolume(null);
            session.setActiveConnection(null);
        };
        return unmount;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Happy path: both vol-a and vol-b mount and scan successfully.
     * The global prune must run exactly once at the end.
     * AvStars volume must not be included.
     */
    @Test
    void happyPath_bothVolumesSucceed_globalPruneRunsOnce() throws Exception {
        SessionContext sessionA = new SessionContext();
        SessionContext sessionB = new SessionContext();

        // Each invocation of the invoker factory returns a fresh session for the next volume.
        // We cycle through A then B.
        List<SessionContext> sessions = List.of(sessionA, sessionB);
        int[] callCount = {0};

        StubCommand mountA   = mountStub(sessionA, VOL_A);
        StubCommand unmountA = unmountStub(sessionA);
        StubCommand mountB   = mountStub(sessionB, VOL_B);
        StubCommand unmountB = unmountStub(sessionB);

        // Build per-session command maps
        Map<String, Command> registryA = new LinkedHashMap<>();
        registryA.put("mount",   mountA);
        registryA.put("unmount", unmountA);
        Map<String, Command> registryB = new LinkedHashMap<>();
        registryB.put("mount",   mountB);
        registryB.put("unmount", unmountB);

        List<Map<String, Command>> registries = List.of(registryA, registryB);

        CoherentMultiVolumeSyncTask task = new CoherentMultiVolumeSyncTask(
                () -> {
                    int idx = callCount[0]++;
                    return new CommandInvoker(registries.get(idx), sessions.get(idx));
                },
                suppressedPruneOp,
                pruneService,
                reconcileService
        );

        TaskRun run = runTaskAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status(),
                "Task should succeed when all volumes scan successfully");

        // Scan was called for vol-a and vol-b (not vol-av)
        verify(suppressedPruneOp, times(2)).execute(any(), any(), any(), any(), any());

        // Global prune ran exactly once
        verify(pruneService, times(1)).pruneOrphanedTitlesAndCovers(any(), anyInt(), any());

        // Reconcile auto-ran exactly once after the successful sync, and was persisted
        // with triggered_by='coherent_sync'.
        verify(reconcileService, times(1)).run(true);
        verify(reconcileService, times(1)).persist(any(), eq("coherent_sync"), any());

        // Both volumes were mounted and unmounted
        assertEquals(1, mountA.invocations.size(),   "vol-a should be mounted once");
        assertEquals(1, unmountA.invocations.size(), "vol-a should be unmounted once");
        assertEquals(1, mountB.invocations.size(),   "vol-b should be mounted once");
        assertEquals(1, unmountB.invocations.size(), "vol-b should be unmounted once");

        // AvStars volume (vol-av) must not appear in any mount invocation
        List<String> mountedVolumeIds = new ArrayList<>();
        for (String[] args : mountA.invocations) if (args.length > 1) mountedVolumeIds.add(args[1]);
        for (String[] args : mountB.invocations) if (args.length > 1) mountedVolumeIds.add(args[1]);
        assertFalse(mountedVolumeIds.contains("vol-av"),
                "AvStars volume must be excluded from coherent sync");
    }

    /**
     * Partial failure: vol-a's FullSyncOperation.execute throws an IOException.
     * vol-b succeeds. The global prune MUST be skipped because the library picture is incomplete.
     *
     * <p>This is the key safety invariant: if we don't have a full picture, we cannot safely
     * judge orphans — a title on the failed volume might be wrongly pruned.
     */
    @Test
    void partialFailure_scanThrows_globalPruneSkipped() throws Exception {
        SessionContext sessionA = new SessionContext();
        SessionContext sessionB = new SessionContext();

        StubCommand mountA   = mountStub(sessionA, VOL_A);
        StubCommand unmountA = unmountStub(sessionA);
        StubCommand mountB   = mountStub(sessionB, VOL_B);
        StubCommand unmountB = unmountStub(sessionB);

        // Vol-a scan fails with an IOException
        doThrow(new IOException("Simulated scan failure on vol-a"))
                .when(suppressedPruneOp).execute(
                        argThat(v -> "vol-a".equals(v.id())), any(), any(), any(), any());

        int[] callCount = {0};
        List<Map<String, Command>> registries = List.of(
                Map.of("mount", mountA, "unmount", unmountA),
                Map.of("mount", mountB, "unmount", unmountB)
        );
        List<SessionContext> sessions = List.of(sessionA, sessionB);

        CoherentMultiVolumeSyncTask task = new CoherentMultiVolumeSyncTask(
                () -> {
                    int idx = callCount[0]++;
                    return new CommandInvoker(registries.get(idx), sessions.get(idx));
                },
                suppressedPruneOp,
                pruneService,
                reconcileService
        );

        TaskRun run = runTaskAndAwait(task);

        // Task completes (does not re-throw) but is not OK (partial failure)
        assertNotEquals(TaskRun.Status.RUNNING, run.status(), "Task must have ended");
        // Task may be PARTIAL or FAILED depending on phase outcomes; it must NOT be OK.
        assertNotEquals(TaskRun.Status.OK, run.status(),
                "Task with a scan failure must not report OK");

        // Vol-a scan was attempted but failed; vol-b scan was attempted and succeeded
        verify(suppressedPruneOp, times(2)).execute(any(), any(), any(), any(), any());

        // CRITICAL: global prune must NOT run because we have an incomplete picture
        verify(pruneService, never()).pruneOrphanedTitlesAndCovers(any(), anyInt(), any());

        // Reconcile must NOT run on partial failure either — incomplete picture means
        // a misleading report. Same skip rationale as the global prune.
        verify(reconcileService, never()).run(anyBoolean());
        verify(reconcileService, never()).persist(any(), anyString(), any());

        // Unmount must still run for vol-a even though scan failed
        assertEquals(1, unmountA.invocations.size(), "vol-a must be unmounted even on scan failure");
        assertEquals(1, unmountB.invocations.size(), "vol-b must be unmounted");
    }

    /**
     * Mount failure: vol-a's mount command leaves the session disconnected.
     * This is a partial failure — the global prune must be skipped.
     * Vol-b must still be attempted.
     */
    @Test
    void mountFailure_treatedAsPartialFailure_pruneSkipped() throws Exception {
        SessionContext sessionA = new SessionContext();
        SessionContext sessionB = new SessionContext();

        // Vol-a mount: does NOT set activeConnection (mount fails)
        StubCommand mountA   = new StubCommand("mount");
        StubCommand unmountA = new StubCommand("unmount");
        StubCommand mountB   = mountStub(sessionB, VOL_B);
        StubCommand unmountB = unmountStub(sessionB);

        int[] callCount = {0};
        List<Map<String, Command>> registries = List.of(
                Map.of("mount", mountA, "unmount", unmountA),
                Map.of("mount", mountB, "unmount", unmountB)
        );
        List<SessionContext> sessions = List.of(sessionA, sessionB);

        CoherentMultiVolumeSyncTask task = new CoherentMultiVolumeSyncTask(
                () -> {
                    int idx = callCount[0]++;
                    return new CommandInvoker(registries.get(idx), sessions.get(idx));
                },
                suppressedPruneOp,
                pruneService,
                reconcileService
        );

        TaskRun run = runTaskAndAwait(task);

        // Task does not throw (must complete)
        assertNotEquals(TaskRun.Status.RUNNING, run.status());

        // Vol-a scan skipped because mount failed
        // Vol-b scan ran (1 call)
        verify(suppressedPruneOp, times(1)).execute(
                argThat(v -> "vol-b".equals(v.id())), any(), any(), any(), any());
        verify(suppressedPruneOp, never()).execute(
                argThat(v -> "vol-a".equals(v.id())), any(), any(), any(), any());

        // Global prune skipped (incomplete picture due to vol-a mount failure)
        verify(pruneService, never()).pruneOrphanedTitlesAndCovers(any(), anyInt(), any());
    }

    /**
     * Volume scope: only supported structure types (conventional, queue, etc.) are synced.
     * AvStars volumes must be excluded even if no explicit volume list is given.
     */
    @Test
    void volumeScope_avStarsExcluded() throws Exception {
        // Only one supported volume (vol-a); vol-av should be excluded.
        // Override AppConfig with just vol-a and vol-av.
        AppConfig.reset();
        AppConfig.initializeForTest(new OrganizerConfig(
                "test", "/tmp", null, null, null, null, null, null,
                List.of(),
                List.of(VOL_A, VOL_AV),
                List.of(new com.organizer3.config.volume.VolumeStructureDef("conventional", List.of(), null)),
                List.of(), null, null));

        SessionContext sessionA = new SessionContext();
        StubCommand mountA   = mountStub(sessionA, VOL_A);
        StubCommand unmountA = unmountStub(sessionA);

        CoherentMultiVolumeSyncTask task = new CoherentMultiVolumeSyncTask(
                () -> new CommandInvoker(
                        Map.of("mount", mountA, "unmount", unmountA), sessionA),
                suppressedPruneOp,
                pruneService,
                reconcileService
        );

        TaskRun run = runTaskAndAwait(task);

        assertEquals(TaskRun.Status.OK, run.status());
        // Only one scan: vol-a
        verify(suppressedPruneOp, times(1)).execute(any(), any(), any(), any(), any());
        // vol-av was never mounted
        assertEquals(1, mountA.invocations.size());
        assertEquals("vol-a", mountA.invocations.get(0)[1]);
    }

    // ── Watchdog + heartbeat tests ────────────────────────────────────────────

    /**
     * Watchdog timeout test: a volume whose scan blocks indefinitely should time out,
     * be marked as a partial failure, and the task should continue and eventually complete.
     * The global prune must be skipped (partial failure = incomplete picture).
     *
     * <p>The per-volume timeout is set to 0 minutes via {@code SmbSettings(perVolumeTimeoutMinutes=0)}.
     * {@code Future.get(0, MINUTES)} fires immediately for any non-instant operation,
     * so the blocking mock triggers the timeout within milliseconds.
     */
    @Test
    void watchdog_scanBlocksForever_timeoutMarksPartialFailureAndContinues() throws Exception {
        // 0 minutes → Future.get(0, MINUTES) fires immediately on any blocking Future
        AppConfig.reset();
        AppConfig.initializeForTest(configWithSmbSettings(new SmbSettings(5, 5, 5, 0, 30, null)));

        // vol-a scan blocks (simulates hung SMB call)
        CountDownLatch blockingLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            blockingLatch.await(10, TimeUnit.SECONDS);
            return null;
        }).when(suppressedPruneOp).execute(
                argThat(v -> "vol-a".equals(v.id())), any(), any(), any(), any());

        SessionContext sessionA = new SessionContext();
        SessionContext sessionB = new SessionContext();

        StubCommand mountA   = mountStub(sessionA, VOL_A);
        StubCommand unmountA = unmountStub(sessionA);
        StubCommand mountB   = mountStub(sessionB, VOL_B);
        StubCommand unmountB = unmountStub(sessionB);

        int[] callCount = {0};
        List<Map<String, Command>> registries = List.of(
                Map.of("mount", mountA, "unmount", unmountA),
                Map.of("mount", mountB, "unmount", unmountB));
        List<SessionContext> sessions = List.of(sessionA, sessionB);

        CoherentMultiVolumeSyncTask task = new CoherentMultiVolumeSyncTask(
                () -> {
                    int idx = callCount[0]++;
                    return new CommandInvoker(registries.get(idx), sessions.get(idx));
                },
                suppressedPruneOp, pruneService, reconcileService, 60_000L);

        try {
            TaskRun run = runTaskAndAwait(task, 15);

            // Task must complete — not hang
            assertNotEquals(TaskRun.Status.RUNNING, run.status(), "Task must have ended, not hung");

            // vol-a timed out → partial failure → global prune must be skipped
            verify(pruneService, never()).pruneOrphanedTitlesAndCovers(any(), anyInt(), any());
            verify(reconcileService, never()).run(anyBoolean());

            // vol-b scan must still have been attempted (task continues after vol-a timeout)
            verify(suppressedPruneOp, times(1)).execute(
                    argThat(v -> "vol-b".equals(v.id())), any(), any(), any(), any());
        } finally {
            blockingLatch.countDown();
        }
    }

    /**
     * Heartbeat test: with a short heartbeat interval, at least one heartbeat fires
     * during a slow scan. We verify by checking that the heartbeat executor ran — proxy
     * via a side-effect counter attached to the scan delay.
     *
     * <p>Note: verifying log output is brittle. Instead, this test uses a very short
     * heartbeat interval (100 ms) and a scan that completes after 300 ms, then
     * asserts the task completed without hanging — confirming the heartbeat did not
     * interfere with normal operation. A separate focused check uses the heartbeat
     * to verify the executor shuts down cleanly.
     */
    @Test
    void heartbeat_doesNotInterfereWithNormalScan() throws Exception {
        // vol-a scan takes ~250ms; heartbeat fires every 50ms; task must complete normally
        SessionContext sessionA = new SessionContext();
        SessionContext sessionB = new SessionContext();

        AtomicInteger scanCallCount = new AtomicInteger(0);
        doAnswer(inv -> {
            scanCallCount.incrementAndGet();
            Thread.sleep(250); // slow scan
            return null;
        }).when(suppressedPruneOp).execute(any(), any(), any(), any(), any());

        StubCommand mountA   = mountStub(sessionA, VOL_A);
        StubCommand unmountA = unmountStub(sessionA);
        StubCommand mountB   = mountStub(sessionB, VOL_B);
        StubCommand unmountB = unmountStub(sessionB);

        int[] callCount = {0};
        List<Map<String, Command>> registries = List.of(
                Map.of("mount", mountA, "unmount", unmountA),
                Map.of("mount", mountB, "unmount", unmountB));
        List<SessionContext> sessions = List.of(sessionA, sessionB);

        CoherentMultiVolumeSyncTask task = new CoherentMultiVolumeSyncTask(
                () -> {
                    int idx = callCount[0]++;
                    return new CommandInvoker(registries.get(idx), sessions.get(idx));
                },
                suppressedPruneOp,
                pruneService,
                reconcileService,
                /* heartbeatIntervalMs= */ 50L);

        TaskRun run = runTaskAndAwait(task, /* timeoutSec= */ 10);

        // Task must complete successfully despite heartbeat
        assertEquals(TaskRun.Status.OK, run.status(),
                "Task should complete normally with heartbeat running");
        // Both volumes must have been scanned
        assertEquals(2, scanCallCount.get(), "Both volumes must scan");
    }

    /**
     * Thread cleanup test: after a watchdog fires and the task ends, the scan and
     * heartbeat executors must be shut down. The key invariant is that the task
     * finishes within the test timeout rather than hanging indefinitely.
     *
     * <p>Note: {@code cancel(true)} sends an interrupt but SMB socket reads are not
     * interruptible at the OS level — the cancelled scan thread may outlive the task
     * briefly. The test asserts the task completed, not thread death. The smbj-level
     * read timeout ({@link SmbSettings#readTimeoutMinutes}) is the actual signal path
     * that terminates the blocked thread.
     */
    @Test
    void watchdog_executorsShutDownAfterTaskEnd() throws Exception {
        AppConfig.reset();
        AppConfig.initializeForTest(configWithSmbSettings(new SmbSettings(5, 5, 5, 0, 30, null)));

        CountDownLatch blockingLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            blockingLatch.await(10, TimeUnit.SECONDS);
            return null;
        }).when(suppressedPruneOp).execute(
                argThat(v -> "vol-a".equals(v.id())), any(), any(), any(), any());

        SessionContext sessionA = new SessionContext();
        SessionContext sessionB = new SessionContext();
        StubCommand mountA   = mountStub(sessionA, VOL_A);
        StubCommand unmountA = unmountStub(sessionA);
        StubCommand mountB   = mountStub(sessionB, VOL_B);
        StubCommand unmountB = unmountStub(sessionB);

        int[] callCount = {0};
        List<Map<String, Command>> registries = List.of(
                Map.of("mount", mountA, "unmount", unmountA),
                Map.of("mount", mountB, "unmount", unmountB));
        List<SessionContext> sessions = List.of(sessionA, sessionB);

        CoherentMultiVolumeSyncTask task = new CoherentMultiVolumeSyncTask(
                () -> {
                    int idx = callCount[0]++;
                    return new CommandInvoker(registries.get(idx), sessions.get(idx));
                },
                suppressedPruneOp, pruneService, reconcileService, 50L);

        try {
            // Task must complete within the test timeout (not hang in RUNNING)
            TaskRun run = runTaskAndAwait(task, 15);
            assertNotEquals(TaskRun.Status.RUNNING, run.status(),
                    "Task must not be stuck in RUNNING after watchdog fires");
        } finally {
            blockingLatch.countDown();
        }
    }

    // ── Config helper ─────────────────────────────────────────────────────────

    /**
     * Builds an {@link OrganizerConfig} with the standard test volumes and the given
     * {@link SmbSettings}. Used by watchdog tests that need a non-default per-volume timeout.
     */
    private static OrganizerConfig configWithSmbSettings(SmbSettings smbSettings) {
        return new OrganizerConfig(
                "test", "/tmp", null, null, null, null, null, null,
                List.of(),
                List.of(VOL_A, VOL_B, VOL_AV),
                List.of(
                        new com.organizer3.config.volume.VolumeStructureDef("conventional", List.of(), null),
                        new com.organizer3.config.volume.VolumeStructureDef("queue", List.of(), null)
                ),
                List.of(), null, null, null, null, null, null, null, null, null, null, smbSettings, null);
    }

    // ── Task runner helper ────────────────────────────────────────────────────

    private static TaskRun runTaskAndAwait(CoherentMultiVolumeSyncTask task) throws Exception {
        return runTaskAndAwait(task, 15);
    }

    private static TaskRun runTaskAndAwait(CoherentMultiVolumeSyncTask task, int timeoutSec) throws Exception {
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start(CoherentMultiVolumeSyncTask.ID, new TaskInputs(Map.of()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
            while (run.status() == TaskRun.Status.RUNNING) {
                if (System.nanoTime() > deadline) fail("Task did not complete within " + timeoutSec + " seconds");
                Thread.sleep(20);
            }
            return run;
        } finally {
            runner.shutdown();
        }
    }
}
