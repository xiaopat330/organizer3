package com.organizer3.utilities.task.organize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.command.Command;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.PartitionDef;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.FreshPrepService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrepTaskTest {

    static final String VOLUME_ID = "unsorted";
    static final VolumeConfig VOLUME_CONFIG =
            new VolumeConfig(VOLUME_ID, "//nas/unsorted", "queue", "nas", null);
    static final VolumeStructureDef QUEUE_STRUCTURE = new VolumeStructureDef(
            "queue",
            List.of(new PartitionDef("queue", "fresh")),
            null);

    private FreshPrepService mockService;
    private OrganizerConfig config;
    private FreshPrepService.Result cannedResult;

    @BeforeEach
    void setUp() throws Exception {
        mockService = mock(FreshPrepService.class);
        config = new OrganizerConfig(
                "Test", null, null, null, null, null, null, null,
                List.of(), List.of(VOLUME_CONFIG), List.of(QUEUE_STRUCTURE), List.of(), null, null);

        cannedResult = new FreshPrepService.Result(
                true, "/fresh", 3,
                List.of(new FreshPrepService.Plan(
                        "/fresh/PRED-848-h265.mkv", "/fresh/(PRED-848)",
                        "/fresh/(PRED-848)/h265", "/fresh/(PRED-848)/h265/PRED-848-h265.mkv",
                        "PRED-848", "h265")),
                List.of(new FreshPrepService.Skip("bad-file.avi", "unparseable")),
                List.of(), List.of());
        when(mockService.plan(any(), any(), anyInt(), anyInt())).thenReturn(cannedResult);
        when(mockService.execute(any(), any(), anyInt(), anyInt())).thenReturn(
                new FreshPrepService.Result(false, "/fresh", 3,
                        cannedResult.planned(), List.of(), cannedResult.planned(), List.of()));
    }

    @Test
    void previewCallsPlanWithDryRun() throws Exception {
        var task = new PrepPreviewTask(mockService, config, () -> buildInvoker(new SessionContext()));
        TaskRun run = runAndAwait(task);
        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).plan(any(), any(), eq(0), eq(0));
        verify(mockService, never()).execute(any(), any(), anyInt(), anyInt());
    }

    @Test
    void executeCallsExecute() throws Exception {
        var task = new PrepTask(mockService, config, () -> buildInvoker(new SessionContext()));
        TaskRun run = runAndAwait(task);
        assertEquals(TaskRun.Status.OK, run.status());
        verify(mockService).execute(any(), any(), eq(0), eq(0));
        verify(mockService, never()).plan(any(), any(), anyInt(), anyInt());
    }

    @Test
    void resultJsonEmbeddedInPrepPhaseEnded() throws Exception {
        var task = new PrepPreviewTask(mockService, config, () -> buildInvoker(new SessionContext()));
        TaskRun run = runAndAwait(task);
        var json = resultJson(run);
        assertTrue(json.has("volumeId"),   "result must contain volumeId");
        assertTrue(json.has("partitions"), "result must contain partitions");
        assertTrue(json.has("summary"),    "result must contain summary");
        assertEquals(1, json.get("partitions").size());
        assertEquals(1, json.get("summary").get("planned").asInt());
        assertEquals(1, json.get("summary").get("skipped").asInt());
    }

    @Test
    void mountAndUnmountPhasesAlwaysRun() throws Exception {
        var task = new PrepPreviewTask(mockService, config, () -> buildInvoker(new SessionContext()));
        TaskRun run = runAndAwait(task);
        var phases = phaseEnded(run);
        assertTrue(phases.stream().anyMatch(p -> "mount".equals(p.phaseId())));
        assertTrue(phases.stream().anyMatch(p -> "unmount".equals(p.phaseId())));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TaskRun runAndAwait(Task task) throws Exception {
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

    private List<TaskEvent.PhaseEnded> phaseEnded(TaskRun run) {
        List<TaskEvent.PhaseEnded> result = new ArrayList<>();
        for (TaskEvent e : run.eventSnapshot()) {
            if (e instanceof TaskEvent.PhaseEnded pe) result.add(pe);
        }
        return result;
    }

    private JsonNode resultJson(TaskRun run) throws Exception {
        String summary = phaseEnded(run).stream()
                .filter(pe -> "prep".equals(pe.phaseId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No PhaseEnded for phaseId=prep"))
                .summary();
        return new ObjectMapper().readTree(summary);
    }

    private CommandInvoker buildInvoker(SessionContext session) {
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

    static final class StubCommand implements Command {
        private final String name;
        Runnable sideEffect = () -> {};

        StubCommand(String name) { this.name = name; }

        @Override public String name()        { return name; }
        @Override public String description() { return ""; }
        @Override public void execute(String[] args, SessionContext ctx, CommandIO io) {
            sideEffect.run();
        }
    }
}
