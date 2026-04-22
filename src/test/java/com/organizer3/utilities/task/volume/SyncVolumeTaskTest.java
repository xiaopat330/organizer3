package com.organizer3.utilities.task.volume;

import com.organizer3.command.Command;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncVolumeTaskTest {

    /** Stub command that records it was called with a given args array and mutates session as directed. */
    private static final class StubCommand implements Command {
        final String name;
        final List<String[]> invocations = new ArrayList<>();
        Runnable sideEffect = () -> {};

        StubCommand(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public String description() { return ""; }
        @Override public void execute(String[] args, SessionContext ctx, CommandIO io) {
            invocations.add(args);
            io.println("[" + name + "] executed with " + String.join(" ", args));
            sideEffect.run();
        }
    }

    @Test
    void runsMountSyncSyncUnmountInOrder() throws Exception {
        StubCommand mount       = new StubCommand("mount");
        StubCommand syncAll     = new StubCommand("sync all");
        StubCommand syncCovers  = new StubCommand("sync covers");
        StubCommand unmount     = new StubCommand("unmount");

        SessionContext session = new SessionContext();
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);
        mount.sideEffect = () -> {
            session.setMountedVolume(new VolumeConfig("a", "//x/y", "conventional", "srv", "g", List.of()));
            session.setActiveConnection(conn);
        };
        unmount.sideEffect = () -> {
            session.setMountedVolume(null);
            session.setActiveConnection(null);
        };

        Map<String, Command> registry = new LinkedHashMap<>();
        registry.put("mount", mount);
        registry.put("sync all", syncAll);
        registry.put("sync covers", syncCovers);
        registry.put("unmount", unmount);

        TaskRun run = runTaskAndAwait(
                new SyncVolumeTask(() -> new CommandInvoker(registry, session)),
                TaskInputs.of("volumeId", "a"));

        assertEquals(TaskRun.Status.OK, run.status());
        assertEquals(1, mount.invocations.size());
        assertEquals("a", mount.invocations.get(0)[1]);
        assertEquals(1, syncAll.invocations.size());
        assertEquals(1, syncCovers.invocations.size());
        assertEquals(1, unmount.invocations.size());

        List<String> phaseIds = run.eventSnapshot().stream()
                .filter(e -> e instanceof TaskEvent.PhaseStarted)
                .map(e -> ((TaskEvent.PhaseStarted) e).phaseId())
                .toList();
        assertEquals(List.of("mount", "syncTitles", "syncCovers", "unmount"), phaseIds);
    }

    @Test
    void skipsSyncPhasesIfMountFailsButStillRunsUnmount() throws Exception {
        StubCommand mount      = new StubCommand("mount");      // leaves session disconnected
        StubCommand syncAll    = new StubCommand("sync all");
        StubCommand syncCovers = new StubCommand("sync covers");
        StubCommand unmount    = new StubCommand("unmount");

        SessionContext session = new SessionContext();
        Map<String, Command> registry = Map.of(
                "mount", mount,
                "sync all", syncAll,
                "sync covers", syncCovers,
                "unmount", unmount);

        TaskRun run = runTaskAndAwait(
                new SyncVolumeTask(() -> new CommandInvoker(registry, session)),
                TaskInputs.of("volumeId", "a"));

        assertEquals(0, syncAll.invocations.size(),    "sync all should not run if mount fails");
        assertEquals(0, syncCovers.invocations.size(), "sync covers should not run if mount fails");
        assertEquals(1, unmount.invocations.size(),    "unmount should always run");
        // Task is partial (mount failed, unmount ok) or failed (no phases ok except unmount).
        assertNotEquals(TaskRun.Status.OK, run.status());
    }

    private static TaskRun runTaskAndAwait(SyncVolumeTask task, TaskInputs inputs) throws Exception {
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start(SyncVolumeTask.ID, inputs);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (run.status() == TaskRun.Status.RUNNING) {
                if (System.nanoTime() > deadline) fail("Task did not complete");
                Thread.sleep(10);
            }
            return run;
        } finally {
            runner.shutdown();
        }
    }
}
