package com.organizer3.utilities.task.organize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.organize.AttentionRouter;
import com.organizer3.organize.OrganizeVolumeService;
import com.organizer3.utilities.task.CommandInvoker;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared mount/service/unmount pattern for all organize pipeline tasks.
 *
 * <p>Subclasses declare their phase set and dryRun flag; this class handles:
 * <ul>
 *   <li>Mounting the volume via CommandInvoker (same pattern as SyncVolumeTask)</li>
 *   <li>Calling OrganizeVolumeService with the subclass's phases + dryRun</li>
 *   <li>Emitting PhaseProgress events per title as they process</li>
 *   <li>Serializing the Result to PhaseEnded.summary as JSON (frontend reads it)</li>
 *   <li>Unmounting unconditionally</li>
 * </ul>
 */
@Slf4j
abstract class OrganizeBaseTask implements Task {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final TaskSpec spec;
    private final Set<OrganizeVolumeService.Phase> phases;
    private final boolean dryRun;
    private final OrganizeVolumeService service;
    private final Jdbi jdbi;
    private final OrganizerConfig config;
    private final Supplier<CommandInvoker> invokerFactory;

    OrganizeBaseTask(
            TaskSpec spec,
            Set<OrganizeVolumeService.Phase> phases,
            boolean dryRun,
            OrganizeVolumeService service,
            Jdbi jdbi,
            OrganizerConfig config,
            Supplier<CommandInvoker> invokerFactory) {
        this.spec = spec;
        this.phases = phases;
        this.dryRun = dryRun;
        this.service = service;
        this.jdbi = jdbi;
        this.config = config;
        this.invokerFactory = invokerFactory;
    }

    @Override
    public TaskSpec spec() { return spec; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) throws Exception {
        String volumeId = inputs.getString("volumeId");
        CommandInvoker invoker = invokerFactory.get();

        boolean mounted = runPhase(io, "mount", "Mount volume", () ->
                invoker.invoke("mount", "mount", new String[]{"mount", volumeId}, io)
                        && invoker.session().isConnected());

        if (mounted && !io.isCancellationRequested()) {
            runOrganize(io, invoker, volumeId);
        } else if (!mounted) {
            io.phaseLog("mount", "Skipping organize phase because mount failed.");
        }

        // Unmount runs unconditionally so we never leave a dangling connection.
        runPhase(io, "unmount", "Unmount volume", () ->
                invoker.invoke("unmount", "unmount", new String[]{"unmount"}, io));
    }

    private void runOrganize(TaskIO io, CommandInvoker invoker, String volumeId) {
        io.phaseStart("organize", dryRun ? "Preview plan" : "Executing");
        try {
            VolumeConfig volumeConfig = config.findById(volumeId).orElse(null);
            if (volumeConfig == null) {
                io.phaseLog("organize", "Volume not in config: " + volumeId);
                io.phaseEnd("organize", "failed", "Volume not in config");
                return;
            }

            VolumeFileSystem fs = invoker.session().getActiveConnection().fileSystem();
            AttentionRouter router = new AttentionRouter(fs, volumeId, Clock.systemUTC());

            OrganizeVolumeService.Result result = service.organize(
                    fs, volumeConfig, router, jdbi, phases, 0, 0, dryRun);

            // Emit progress: total titles processed
            io.phaseProgress("organize", result.titlesInSlice(), result.queueTotal(), "");

            // Serialize result as JSON so the frontend can render the plan/summary table
            String resultJson = JSON.writeValueAsString(result);
            io.phaseEnd("organize", "ok", resultJson);

        } catch (Exception e) {
            log.error("Organize task failed for volume {}", volumeId, e);
            io.phaseLog("organize", "Organize failed: " + e.getMessage());
            io.phaseEnd("organize", "failed", e.getMessage());
        }
    }

    /** Opens a phase, runs the body, closes with ok/failed based on the returned success flag. */
    static boolean runPhase(TaskIO io, String id, String label,
                            java.util.function.Supplier<Boolean> body) {
        io.phaseStart(id, label);
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(body.get());
        } catch (RuntimeException e) {
            io.phaseLog(id, "Phase threw: " + e.getMessage());
            ok = false;
        }
        io.phaseEnd(id, ok ? "ok" : "failed", "");
        return ok;
    }

    /** Build the standard TaskSpec for an organize task. */
    static TaskSpec spec(String id, String title, String description) {
        return new TaskSpec(id, title, description,
                List.of(new TaskSpec.InputSpec(
                        "volumeId", "Volume", TaskSpec.InputSpec.InputType.VOLUME_ID, true)));
    }
}
