package com.organizer3.utilities.task.organize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.command.Command;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.db.SchemaInitializer;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskEvent;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskRegistry;
import com.organizer3.utilities.task.TaskRun;
import com.organizer3.utilities.task.TaskRunner;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared scaffolding for organize task tests. Provides a stubbed mount/unmount environment,
 * a mocked OrganizeVolumeService returning a canned Result, and helpers for running tasks
 * and asserting on their events.
 */
abstract class OrganizeTaskTestBase {

    static final String VOLUME_ID = "a";
    static final VolumeConfig VOLUME_CONFIG =
            new VolumeConfig(VOLUME_ID, "//nas/jav_a", "conventional", "nas", null);

    Connection connection;
    Jdbi jdbi;
    OrganizeVolumeService mockService;
    OrganizerConfig orgConfig;
    OrganizeVolumeService.Result cannedResult;

    @BeforeEach
    void baseSetUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(connection);
        new SchemaInitializer(jdbi).initialize();

        mockService = mock(OrganizeVolumeService.class);
        orgConfig = buildConfig();

        cannedResult = new OrganizeVolumeService.Result(
                VOLUME_ID, true, Set.of(OrganizeVolumeService.Phase.NORMALIZE),
                0, 1, 3,
                List.of(), List.of(),
                new OrganizeVolumeService.Summary(1, 1, 0, 0, 0, 0, 0, 0, 0));
        when(mockService.organize(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(cannedResult);
    }

    @AfterEach
    void baseTearDown() throws Exception {
        connection.close();
    }

    /** Run the given task and wait up to 5 s for completion. */
    TaskRun runAndAwait(Task task) throws Exception {
        TaskRunner runner = new TaskRunner(new TaskRegistry(List.of(task)));
        try {
            TaskRun run = runner.start(task.spec().id(), TaskInputs.of("volumeId", VOLUME_ID));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (run.status() == TaskRun.Status.RUNNING) {
                if (System.nanoTime() > deadline) throw new AssertionError("Task did not complete");
                Thread.sleep(10);
            }
            return run;
        } finally {
            runner.shutdown();
        }
    }

    /** Build a CommandInvoker whose mount stub sets up a connected session. */
    CommandInvoker buildInvoker(SessionContext session) {
        VolumeFileSystem mockFs = mock(VolumeFileSystem.class);
        VolumeConnection mockConn = mock(VolumeConnection.class);
        when(mockConn.isConnected()).thenReturn(true);
        when(mockConn.fileSystem()).thenReturn(mockFs);

        StubCommand mount = new StubCommand("mount");
        mount.sideEffect = () -> {
            session.setMountedVolume(VOLUME_CONFIG);
            session.setActiveConnection(mockConn);
        };
        StubCommand unmount = new StubCommand("unmount");
        unmount.sideEffect = () -> {
            session.setMountedVolume(null);
            session.setActiveConnection(null);
        };

        Map<String, Command> cmds = new LinkedHashMap<>();
        cmds.put("mount", mount);
        cmds.put("unmount", unmount);
        return new CommandInvoker(cmds, session);
    }

    /** Extract all PhaseEnded events from the run's snapshot. */
    List<TaskEvent.PhaseEnded> phaseEnded(TaskRun run) {
        List<TaskEvent.PhaseEnded> result = new ArrayList<>();
        for (TaskEvent e : run.eventSnapshot()) {
            if (e instanceof TaskEvent.PhaseEnded pe) result.add(pe);
        }
        return result;
    }

    /** Find the PhaseEnded event for a specific phaseId. */
    TaskEvent.PhaseEnded phaseEnded(TaskRun run, String phaseId) {
        return phaseEnded(run).stream()
                .filter(pe -> phaseId.equals(pe.phaseId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No PhaseEnded for phaseId=" + phaseId));
    }

    /** Parse the JSON summary from the "organize" PhaseEnded event. */
    JsonNode resultJson(TaskRun run) throws Exception {
        String summary = phaseEnded(run, "organize").summary();
        return new ObjectMapper().readTree(summary);
    }

    private static OrganizerConfig buildConfig() {
        return new OrganizerConfig(
                "Test", null, null, null, null, null, null, null,
                List.of(), List.of(VOLUME_CONFIG), List.of(), List.of(), null);
    }

    // ── stub command ──────────────────────────────────────────────────────────

    static final class StubCommand implements Command {
        private final String name;
        final List<String[]> invocations = new ArrayList<>();
        Runnable sideEffect = () -> {};

        StubCommand(String name) { this.name = name; }

        @Override public String name()        { return name; }
        @Override public String description() { return ""; }
        @Override public void execute(String[] args, SessionContext ctx, CommandIO io) {
            invocations.add(args);
            sideEffect.run();
        }
    }
}
